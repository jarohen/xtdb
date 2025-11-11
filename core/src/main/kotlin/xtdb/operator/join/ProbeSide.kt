package xtdb.operator.join

import xtdb.arrow.EquiComparator3
import xtdb.arrow.RelationReader
import xtdb.expression.map.IndexHasher.Companion.hasher
import java.util.function.BiConsumer

class ProbeSide(
    private val buildSide: BuildSide, val probeRel: RelationReader,
    val keyColNames: List<String>, private val comparator: EquiComparator3,
) {

    internal val buildRel = buildSide.dataRel
    val nullRowIdx get() = buildSide.nullRowIdx
    val rowCount = probeRel.rowCount

    private val hasher = probeRel.hasher(keyColNames)

    fun forEachIndexOf(c: BiConsumer<Int, Int>, removeOnMatch: Boolean) =
        repeat(rowCount) { probeIdx ->
            val buildIdx = buildSide.indexOf(
                hasher.hashCode(probeIdx),
                { buildIdx -> comparator.equals2(buildIdx, probeIdx) },
                removeOnMatch
            )
            c.accept(probeIdx, buildIdx)
            if (buildIdx >= 0) buildSide.addMatch(buildIdx)
        }

    fun iterator(probeIdx: Int): IntIterator {
        val hashCode = hasher.hashCode(probeIdx)
        val iter = buildSide.iterator(hashCode)

        return object : IntIterator() {
            private var nextValue = -1
            private var hasNextValue = false

            override fun hasNext(): Boolean {
                if (hasNextValue) return true

                while (iter.hasNext()) {
                    val idx = iter.nextInt()
                    if (comparator.equals2(idx, probeIdx)) {
                        nextValue = idx
                        hasNextValue = true
                        buildSide.addMatch(idx)
                        return true
                    }
                }
                return false
            }

            override fun nextInt(): Int {
                if (!hasNext()) throw NoSuchElementException()
                hasNextValue = false
                return nextValue
            }
        }
    }

    fun matches(probeIdx: Int): Boolean? {
        // TODO: This doesn't use the hashTries, still a nested loop join
        var seenNull = false
        val buildRowCount = buildRel.rowCount
        for (buildIdx in 0 until buildRowCount) {
            val res = comparator.equals3(buildIdx, probeIdx)
            when (res) {
                true -> return true
                null -> seenNull = true
                false -> Unit
            }
        }
        return if (seenNull) null else false
    }
}
