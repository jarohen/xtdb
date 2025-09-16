package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.Schema
import org.roaringbitmap.RoaringBitmap
import xtdb.arrow.Relation
import xtdb.arrow.Relation.RelationUnloader
import xtdb.arrow.RelationReader
import xtdb.arrow.VectorWriter
import xtdb.expression.map.IndexHasher
import xtdb.types.Type.Companion.I32
import xtdb.types.Type.Companion.ofType
import xtdb.types.schema
import xtdb.util.closeOnCatch
import xtdb.util.openWritableChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.IntConsumer
import java.util.function.IntUnaryOperator
import kotlin.io.path.deleteIfExists

private const val HASH_COL_NAME = "xt/join-hash"

class BuildSide(
    private val al: BufferAllocator,
    val schema: Schema,
    val keyColNames: List<String>,
    val matchedBuildIdxs: RoaringBitmap?,
    val withNilRow: Boolean,
    val inMemoryThreshold: Int = 1_000_000,
) : AutoCloseable {
    private val dataRel = Relation(al, schema)
    private val dataRelHasher = IndexHasher.fromCols(keyColNames.map { dataRel[it] })

    private var builtDataRel: RelationReader? = null
    val builtRel get() = builtDataRel!!
    var buildMap: BuildSideMap? = null; private set

    internal fun hashDataRel(dataRel: Relation, hashCol: VectorWriter) {
        hashCol.clear()

        repeat(dataRel.rowCount) { idx ->
            hashCol.writeInt(dataRelHasher.hashCode(idx))
        }
    }

    internal class Spill(
        private val al: BufferAllocator,
        val path: Path, private val ch: WritableByteChannel, private val unloader: RelationUnloader,
    ) : AutoCloseable {

        var rowCount: Int = 0; private set

        fun spill(dataRel: Relation) {
            if (dataRel.rowCount == 0) return
            rowCount += dataRel.rowCount
            unloader.writePage()
            dataRel.clear()
        }

        fun openDataLoader() = Relation.loader(al, path)

        fun end() {
            unloader.end()
        }

        override fun close() {
            unloader.close()
            ch.close()
            Files.deleteIfExists(path)
        }
    }

    private fun openSpiller(): Spill {
        val dataPath = Files.createTempFile("xtdb-build-side-", ".arrow")

        return dataPath.openWritableChannel().closeOnCatch { dataCh ->
            dataRel.startUnload(dataCh).closeOnCatch { dataUnloader ->
                Spill(al, dataPath, dataCh, dataUnloader)
            }
        }
    }

    internal var spill: Spill? = null; private set

    @Suppress("NAME_SHADOWING")
    fun append(inRel: RelationReader) {
        inRel.openDirectSlice(al).use { inRel ->
            val rowCopier = inRel.rowCopier(dataRel)

            repeat(inRel.rowCount) { inIdx ->
                rowCopier.copyRow(inIdx)
            }

            if (dataRel.rowCount > inMemoryThreshold) {
                val spill = spill ?: openSpiller().also { this.spill = it }

                spill.spill(dataRel)
            }
        }
    }

    internal class Shuffle(
        private val al: BufferAllocator,
        val shuffleDataFile: Path,
        val shuffleHashFile: Path,
    ) : AutoCloseable {

        fun openDataLoader() = Relation.loader(al, shuffleDataFile)
        fun openHashLoader() = Relation.loader(al, shuffleHashFile)

        // temporary function to load everything back in
        fun loadAll(dataRel: Relation, hashRel: Relation) {
            dataRel.clear()
            hashRel.clear()

            openDataLoader().use { loader ->
                Relation(al, loader.schema).use { inRel ->
                    while (loader.loadNextPage(inRel))
                        dataRel.append(inRel)
                }
            }

            openHashLoader().use { loader ->
                Relation(al, loader.schema).use { inRel ->
                    while (loader.loadNextPage(inRel))
                        hashRel.append(inRel)
                }
            }
        }

        override fun close() {
            shuffleHashFile.deleteIfExists()
            shuffleDataFile.deleteIfExists()
        }
    }

    internal var shuffle: Shuffle? = null; private set

    internal fun Spill.openShuffle(): Shuffle {
        val shuffleHashFile = Files.createTempFile("xtdb-build-side-shuffle-hash-", ".arrow")

        // step 1 - just hashes
        openDataLoader().use { dataLoader ->
            Relation(al, schema(HASH_COL_NAME ofType I32)).use { hashRel ->
                val hashCol = hashRel[HASH_COL_NAME]
                shuffleHashFile.openWritableChannel().use { outCh ->
                    hashRel.startUnload(outCh).use { unloader ->
                        while (dataLoader.loadNextPage(dataRel)) {
                            hashDataRel(dataRel, hashCol)
                            unloader.writePage()
                        }

                        unloader.end()
                    }
                }
            }
        }

        return Shuffle(al, this.path, shuffleHashFile)
    }


    fun build() {
        Relation(al, schema(HASH_COL_NAME ofType I32)).use { hashRel ->
            val hashCol = hashRel[HASH_COL_NAME]

            val spill = this.spill

            if (spill != null) {
                spill.spill(dataRel)
                spill.end()

                val shuffle = spill.openShuffle().also { this.shuffle = it }
                shuffle.loadAll(dataRel, hashRel)
            } else{
                hashDataRel(dataRel, hashCol)
            }

            if (withNilRow) dataRel.endRow()
            builtDataRel?.close()
            builtDataRel = RelationReader.from(dataRel.openAsRoot(al))

            buildMap?.close()
            buildMap = BuildSideMap.from(al, hashCol)
        }
    }

    val nullRowIdx: Int
        get() {
            check(withNilRow) { "no nil row in build side" }
            return builtRel.rowCount - 1
        }

    fun addMatch(idx: Int) = matchedBuildIdxs?.add(idx)

    fun indexOf(hashCode: Int, cmp: IntUnaryOperator, removeOnMatch: Boolean): Int =
        requireNotNull(buildMap).findValue(hashCode, cmp, removeOnMatch)

    fun forEachMatch(hashCode: Int, c: IntConsumer) =
        requireNotNull(buildMap).forEachMatch(hashCode, c)

    override fun close() {
        buildMap?.close()
        builtDataRel?.close()

        shuffle?.close()
        spill?.close()

        dataRel.close()
    }
}
