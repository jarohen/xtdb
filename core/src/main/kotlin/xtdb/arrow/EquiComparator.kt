package xtdb.arrow

import org.apache.arrow.memory.util.ArrowBufPointer

fun interface EquiComparator3 {
    fun equals2(leftIdx: Int, rightIdx: Int) = equals3(leftIdx, rightIdx) ?: false
    fun equals3(leftIdx: Int, rightIdx: Int): Boolean?
    fun flip() = EquiComparator3 { leftIdx, rightIdx -> equals3(rightIdx, leftIdx) }

    fun and(other: EquiComparator3) = EquiComparator3 { l, r -> equals3(l, r).and3 { other.equals3(l, r) } }

    companion object {
        inline fun Boolean?.and3(r: () -> Boolean?): Boolean? {
            if (this == false) return false
            val other = r()
            if (other == false) return false
            if (this == null || other == null) return null
            return this && other
        }
    }
}

fun interface EquiComparator2 : EquiComparator3 {
    override fun equals3(leftIdx: Int, rightIdx: Int): Boolean? = equals2(leftIdx, rightIdx)
    override fun equals2(leftIdx: Int, rightIdx: Int): Boolean
    override fun flip() = EquiComparator2 { leftIdx, rightIdx -> this.equals2(rightIdx, leftIdx) }
    fun and(other: EquiComparator2) = EquiComparator2 { l, r -> this.equals2(l, r) && other.equals2(l, r) }

    object Always : EquiComparator2 {
        override fun equals2(leftIdx: Int, rightIdx: Int) = true
        override fun flip() = Always
        override fun and(other: EquiComparator2) = other
    }

    object Never : EquiComparator2 {
        override fun equals2(leftIdx: Int, rightIdx: Int) = false
        override fun flip() = Never
        override fun and(other: EquiComparator2) = Never
    }

    class CheckingNull(val left: VectorReader, val right: VectorReader, val inner: EquiComparator2) : EquiComparator3 {
        override fun equals3(leftIdx: Int, rightIdx: Int): Boolean? =
            if (left.isNull(leftIdx) || right.isNull(rightIdx)) null else inner.equals2(leftIdx, rightIdx)
    }

    class ByPointer(val left: VectorReader, val right: VectorReader) : EquiComparator2 {
        private val leftPtr = ArrowBufPointer()
        private val rightPtr = ArrowBufPointer()

        override fun equals2(leftIdx: Int, rightIdx: Int): Boolean =
            0 == left.getPointer(leftIdx, leftPtr).compareTo(right.getPointer(rightIdx, rightPtr))
    }
}

