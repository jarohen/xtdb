package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.Schema
import org.roaringbitmap.RoaringBitmap
import xtdb.arrow.Relation
import xtdb.arrow.RelationReader
import xtdb.arrow.Vector
import xtdb.arrow.Vector.Companion.openVector
import xtdb.expression.map.IndexHasher.Companion.hasher
import xtdb.types.Type
import xtdb.types.Type.Companion.I32
import xtdb.types.Type.Companion.ofType
import java.util.function.IntConsumer
import java.util.function.IntUnaryOperator

class BuildSide(
    private val al: BufferAllocator,
    val schema: Schema,
    val keyColNames: List<String>,
    val matchedBuildIdxs: RoaringBitmap?,
    val withNilRow: Boolean,
    val inMemoryThreshold: Int = 1_000_000,
) : AutoCloseable {
    private val dataRel = Relation(al, schema)

    private var builtDataRel: RelationReader? = null
    val builtRel get() = builtDataRel!!
    var buildMap: BuildSideMap? = null; private set

    internal var spill: Spill? = null; private set

    @Suppress("NAME_SHADOWING")
    fun append(inRel: RelationReader) {
        inRel.openDirectSlice(al).use { inRel ->
            val rowCopier = inRel.rowCopier(dataRel)

            repeat(inRel.rowCount) { inIdx ->
                rowCopier.copyRow(inIdx)
            }

            if (dataRel.rowCount > inMemoryThreshold) {
                val spill = spill ?: Spill.open(al, dataRel).also { this.spill = it }

                spill.spill()
            }
        }
    }

    internal var shuffle: Shuffle? = null; private set

    fun build() {
        "hashes".ofType(I32).openVector(al).use { hashCol ->
            val spill = this.spill

            if (spill != null) {
                spill.spill()
                spill.end()

                val shuffle = Shuffle.open(
                    al, dataRel, keyColNames, spill.rowCount, spill.blockCount
                ).also { this.shuffle = it }

                spill.openDataLoader().use { dataLoader ->
                    while(dataLoader.loadNextPage(dataRel)) {
                        shuffle.shuffle()
                    }
                }

                shuffle.end()
                shuffle.loadAllData(dataRel)
                shuffle.loadAllHashes(hashCol)
            } else {
                dataRel.hasher(keyColNames).writeAllHashes(hashCol)
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
