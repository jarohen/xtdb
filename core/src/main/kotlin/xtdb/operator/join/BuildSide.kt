package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.Schema
import org.roaringbitmap.RoaringBitmap
import xtdb.arrow.Relation
import xtdb.arrow.Relation.RelationUnloader
import xtdb.arrow.RelationReader
import xtdb.expression.map.IndexHasher
import xtdb.types.Type
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

    private val hashRel = Relation(al, schema("xt/join-hash" ofType Type.I32))
    private val hashColumn = hashRel["xt/join-hash"]

    private var builtDataRel: RelationReader? = null
    val builtRel get() = builtDataRel!!
    var buildMap: BuildSideMap? = null

    init {
        if (withNilRow) {
            dataRel.endRow()
        }
    }

    internal inner class Spill(
        val dataPath: Path, private val dataCh: WritableByteChannel, private val dataUnloader: RelationUnloader,
        val hashPath: Path, private val hashCh: WritableByteChannel, private val hashUnloader: RelationUnloader,
    ) : AutoCloseable {
        fun spill() {
            dataUnloader.writePage()
            dataRel.clear()
            hashUnloader.writePage()
            hashRel.clear()
        }

        fun end() {
            dataUnloader.end()
            hashUnloader.end()
        }

        override fun close() {
            dataUnloader.close()
            dataCh.close()
            Files.deleteIfExists(dataPath)

            hashUnloader.close()
            hashCh.close()
            Files.deleteIfExists(hashPath)
        }
    }

    private fun openSpiller(): Spill {
        val dataPath = Files.createTempFile("xtdb-build-side-", ".arrow")
        val hashPath = Files.createTempFile("xtdb-build-side-hash-", ".arrow")

        return dataPath.openWritableChannel().closeOnCatch { dataCh ->
            dataRel.startUnload(dataCh).closeOnCatch { dataUnloader ->
                hashPath.openWritableChannel().closeOnCatch { hashCh ->
                    hashRel.startUnload(hashCh).closeOnCatch { hashUnloader ->
                        Spill(dataPath, dataCh, dataUnloader, hashPath, hashCh, hashUnloader)
                    }
                }
            }
        }
    }

    internal var spill: Spill? = null; private set

    @Suppress("NAME_SHADOWING")
    fun append(inRel: RelationReader) {
        inRel.openDirectSlice(al).use { inRel ->
            val inKeyCols = keyColNames.map { inRel[it] }

            val hasher = IndexHasher.fromCols(inKeyCols)
            val rowCopier = inRel.rowCopier(dataRel)

            repeat(inRel.rowCount) { inIdx ->
                hashColumn.writeInt(hasher.hashCode(inIdx))
                rowCopier.copyRow(inIdx)
            }

            if (dataRel.rowCount > inMemoryThreshold) {
                val spill = spill ?: openSpiller().also { this.spill = it }

                spill.spill()
            }
        }
    }

    fun build() {
        spill?.let { spill ->
            spill.spill()
            spill.end()

            Relation.loader(al, spill.dataPath).use { loader ->
                Relation(al, loader.schema).use { inRel ->
                    while (loader.loadNextPage(inRel))
                        dataRel.append(inRel)
                }
            }

            Relation.loader(al, spill.hashPath).use { loader ->
                Relation(al, loader.schema).use { inRel ->
                    while (loader.loadNextPage(inRel))
                        hashRel.append(inRel)
                }
            }
        }

        builtDataRel?.close()
        builtDataRel = RelationReader.from(dataRel.openAsRoot(al))

        buildMap?.close()
        buildMap = BuildSideMap.from(al, hashColumn, if (withNilRow) 1 else 0)
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
        hashRel.close()
    }
}
