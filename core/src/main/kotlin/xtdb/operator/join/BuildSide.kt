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

internal const val NULL_ROW_IDX = 0

class BuildSide(
    val al: BufferAllocator,
    val schema: Schema,
    val keyColNames: List<String>,
    val matchedBuildIdxs: RoaringBitmap?,
    private val withNilRow: Boolean,
    private val inMemoryThreshold: Int = 1_000_000,
) : AutoCloseable {
    private val dataRel = Relation(al, schema)

    private var builtDataRel: RelationReader? = null
    val builtRel get() = builtDataRel!!
    var buildMap: BuildSideMap? = null

    init {
        if (withNilRow) {
            dataRel.endRow()
        }
    }

    internal fun hashDataRel(dataRel: Relation, hashCol: VectorWriter) {
        hashCol.clear()

        val hasher = IndexHasher.fromCols(keyColNames.map { dataRel[it] })
        val offset = if (withNilRow) 1 else 0
        repeat(dataRel.rowCount - offset) { idx ->
            hashCol.writeInt(hasher.hashCode(idx + offset))
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

        fun loadAll(dataRel: Relation) {
            openDataLoader().use { loader ->
                Relation(al, loader.schema).use { inRel ->
                    while (loader.loadNextPage(inRel))
                        dataRel.append(inRel)
                }
            }
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

    fun build() {
        Relation(al, schema("xt/join-hash" ofType I32)).use { hashRel ->
            val hashCol = hashRel["xt/join-hash"]

            val spill = this.spill

            if (spill != null) {
                spill.spill(dataRel)
                spill.end()

                spill.loadAll(dataRel)
            }

            hashDataRel(dataRel, hashCol)

            builtDataRel?.close()
            builtDataRel = RelationReader.from(dataRel.openAsRoot(al))

            buildMap?.close()
            buildMap = BuildSideMap.from(al, hashCol, if (withNilRow) 1 else 0)
        }
    }

    fun addMatch(idx: Int) = matchedBuildIdxs?.add(idx)

    fun indexOf(hashCode: Int, cmp: IntUnaryOperator, removeOnMatch: Boolean): Int =
        requireNotNull(buildMap).findValue(hashCode, cmp, removeOnMatch)

    fun forEachMatch(hashCode: Int, c: IntConsumer) =
        requireNotNull(buildMap).forEachMatch(hashCode, c)

    override fun close() {
        buildMap?.close()
        builtDataRel?.close()

        spill?.close()

        dataRel.close()
    }
}
