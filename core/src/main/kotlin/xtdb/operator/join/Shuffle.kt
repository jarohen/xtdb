package xtdb.operator.join

import com.carrotsearch.hppc.IntArrayList
import org.apache.arrow.memory.BufferAllocator
import xtdb.arrow.Relation
import xtdb.arrow.Relation.RelationUnloader
import xtdb.arrow.Vector
import xtdb.arrow.Vector.Companion.openVector
import xtdb.arrow.VectorWriter
import xtdb.expression.map.IndexHasher.Companion.hasher
import xtdb.trie.ColumnName
import xtdb.types.Type
import xtdb.types.Type.Companion.I32
import xtdb.types.Type.Companion.ofType
import xtdb.util.closeOnCatch
import xtdb.util.deleteOnCatch
import java.nio.file.Files.createTempFile
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

internal const val HASH_COL_NAME = "xt/join-hash"

internal class Shuffle private constructor(
    private val al: BufferAllocator, private val inDataRel: Relation, hashColNames: List<ColumnName>,

    val dataFile: Path, private val outDataRel: Relation, private val dataUnloader: RelationUnloader,
    val hashFile: Path, private val hashRel: Relation, private val hashUnloader: RelationUnloader,

    expectedRowCount: Long, expectedBlockCount: Int
) : AutoCloseable {

    val partCount = (expectedBlockCount.takeHighestOneBit() shl 1).coerceAtLeast(1)
    val hashMask = partCount - 1
    val approxRowsPerPart = (expectedRowCount / expectedBlockCount / partCount).toInt()
    private val hashCol = hashRel[HASH_COL_NAME]

    private val dataRowCopier = inDataRel.rowCopier(outDataRel)
    private val hasher = inDataRel.hasher(hashColNames)

    fun shuffle() {
        val selections = Array(partCount) { IntArrayList(approxRowsPerPart) }

        HASH_COL_NAME.ofType(I32).openVector(al).use { tmpHashCol ->
            repeat(inDataRel.rowCount) { inIdx ->
                val hashCode = hasher.hashCode(inIdx)
                tmpHashCol.writeInt(hashCode)
                selections[hashCode and hashMask].add(inIdx)
            }

            val hashCopier = tmpHashCol.rowCopier(hashCol)

            for (selection in selections) {
                outDataRel.clear()
                hashRel.clear()

                for (selIdx in selection.iterator()) {
                    dataRowCopier.copyRow(selIdx.value)
                    hashCopier.copyRow(selIdx.value)
                }

                dataUnloader.writePage()
                hashUnloader.writePage()
            }
        }
    }

    fun end() {
        dataUnloader.end()
        hashUnloader.end()
    }

    // temporary function to load data back in
    fun loadAllData(dataRel: Relation) {
        dataRel.clear()
        Relation.loader(al, dataFile).use { loader ->
            Relation(al, loader.schema).use { inRel ->
                while (loader.loadNextPage(inRel)) {
                    dataRel.append(inRel)
                }
            }
        }
    }

    // temporary function to load hashes back in
    fun loadAllHashes(hashCol: VectorWriter) {
        hashCol.clear()
        Relation.loader(al, hashFile).use { loader ->
            Relation(al, loader.schema).use { inRel ->
                val inCol = inRel[HASH_COL_NAME]
                while (loader.loadNextPage(inRel))
                    hashCol.append(inCol)
            }
        }
    }

    override fun close() {
        dataUnloader.close()
        outDataRel.close()

        hashUnloader.close()
        hashRel.close()

        hashFile.deleteIfExists()
        dataFile.deleteIfExists()
    }

    companion object {
        // my kingdom for `util/with-close-on-catch` in Kotlin
        // y'all need macros. or monads.

        fun open(
            al: BufferAllocator, inDataRel: Relation, hashColNames: List<ColumnName>,
            rowCount: Long, blockCount: Int
        ): Shuffle =
            createTempFile("xtdb-build-side-shuffle-", ".arrow").deleteOnCatch { dataFile ->
                Relation(al, inDataRel.schema).closeOnCatch { outDataRel ->
                    outDataRel.startUnload(dataFile).closeOnCatch { dataUnloader ->

                        createTempFile("xtdb-build-side-shuffle-hash-", ".arrow").deleteOnCatch { hashFile ->
                            Relation(al, HASH_COL_NAME ofType I32).closeOnCatch { outHashRel ->
                                outHashRel.startUnload(hashFile).closeOnCatch { hashUnloader ->

                                    Shuffle(
                                        al, inDataRel, hashColNames,
                                        dataFile, outDataRel, dataUnloader,
                                        hashFile, outHashRel, hashUnloader,
                                        rowCount, blockCount
                                    )

                                }
                            }
                        }

                    }
                }
            }
    }
}
