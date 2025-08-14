package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import xtdb.arrow.IntVector
import xtdb.util.closeOnCatch
import java.util.function.IntConsumer

private const val DEFAULT_LOAD_FACTOR = 0.6

class BuildSideMap private constructor(
    private val srcIdxs: IntVector,
    private val srcHashes: IntVector,
    private val hashMask: Int,
) : AutoCloseable {

    fun forEachMatch(hash: Int, c: IntConsumer) {
        val valCount = srcIdxs.valueCount
        var lookupIdx = hash and hashMask
        var iterationCount = 0

        while (true) {
            if (srcIdxs.isNull(lookupIdx)) return
            if (srcHashes.getInt(lookupIdx) == hash) c.accept(srcIdxs.getInt(lookupIdx))

            iterationCount++
            lookupIdx = (lookupIdx + iterationCount * iterationCount).mod(valCount)
        }
    }

    override fun close() {
        srcHashes.close()
        srcIdxs.close()
    }

    companion object {
        internal fun hashBits(rowCount: Int, loadFactor: Double = DEFAULT_LOAD_FACTOR) =
            Long.SIZE_BITS - (rowCount / loadFactor).toLong().countLeadingZeroBits()

        internal fun IntVector.insertionIdx(maskedHash: Int): Int {
            val valCount = valueCount

            var insertionIdx = maskedHash
            var iterationCount = 0

            while (true) {
                if (isNull(insertionIdx)) return insertionIdx
                iterationCount++
                insertionIdx = (insertionIdx + iterationCount * iterationCount).mod(valCount)
            }
        }

        @JvmStatic
        @JvmOverloads
        fun from(al: BufferAllocator, hashCol: IntVector, loadFactor: Double = DEFAULT_LOAD_FACTOR): BuildSideMap {

            val rowCount = hashCol.valueCount

            val hashBits = hashBits(rowCount, loadFactor)
            val mapSize = 1 shl hashBits
            val hashMask = mapSize - 1

            return IntVector(al, "src-idxs", true, mapSize).closeOnCatch { srcIdxs ->
                IntVector(al, "src-hashes", true, mapSize).closeOnCatch { srcHashes ->
                    repeat(rowCount) { idx ->
                        val hash = hashCol.getInt(idx)
                        val insertionIdx = srcIdxs.insertionIdx(hash and hashMask)
                        srcIdxs[insertionIdx] = idx
                        srcHashes[insertionIdx] = hash
                    }

                    BuildSideMap(srcIdxs, srcHashes, hashMask)
                }
            }
        }
    }
}