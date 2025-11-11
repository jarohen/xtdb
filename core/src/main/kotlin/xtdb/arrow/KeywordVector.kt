package xtdb.arrow

import clojure.lang.Keyword
import org.apache.arrow.memory.BufferAllocator
import xtdb.api.query.IKeyFn
import xtdb.arrow.EquiComparator2.ByPointer
import xtdb.arrow.EquiComparator2.Never
import xtdb.arrow.metadata.MetadataFlavour
import xtdb.vector.extensions.KeywordType

class KeywordVector(override val inner: Utf8Vector) : ExtensionVector(), MetadataFlavour.Bytes {

    override val arrowType = KeywordType

    override fun getObject0(idx: Int, keyFn: IKeyFn<*>) = Keyword.intern(inner.getObject0(idx, keyFn))

    override fun writeObject0(value: Any) = when (value) {
        is Keyword -> inner.writeObject(value.sym.toString())
        else -> throw InvalidWriteObjectException(fieldType, value)
    }

    override val metadataFlavours get() = listOf(this)

    override fun equiComparator2(other: Vector) =
        if (other is KeywordVector) ByPointer(this, other) else Never

    override fun openSlice(al: BufferAllocator) = KeywordVector(inner.openSlice(al))
}