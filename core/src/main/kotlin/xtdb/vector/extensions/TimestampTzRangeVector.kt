package xtdb.vector.extensions

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.complex.FixedSizeListVector
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.FieldType
import xtdb.time.TimestampTzRange
import xtdb.vector.from
import java.time.ZonedDateTime

class TimestampTzRangeVector(name: String, allocator: BufferAllocator, fieldType: FieldType) :
    XtExtensionVector<FixedSizeListVector>(
        name,
        allocator,
        fieldType,
        FixedSizeListVector(name, allocator, FieldType.notNullable(ArrowType.FixedSizeList(2)), null)
    ) {

    private val underlyingDataVector get() = from(underlyingVector.dataVector)

    private fun getFrom(index: Int) = underlyingDataVector.getObject(index * 2) as ZonedDateTime?
    private fun getTo(index: Int) = underlyingDataVector.getObject(index * 2 + 1) as ZonedDateTime?

    override fun getObject0(index: Int): TimestampTzRange = TimestampTzRange(getFrom(index), getTo(index))
}
