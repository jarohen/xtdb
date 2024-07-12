package xtdb.vector.extensions

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.types.pojo.ExtensionTypeRegistry
import org.apache.arrow.vector.types.pojo.FieldType

object TimestampTzRangeType : XtExtensionType("xtdb/timestamp-tz-range", FixedSizeList(2)) {
    init {
        ExtensionTypeRegistry.register(this)
    }

    override fun deserialize(serializedData: String) = this

    override fun serialize(): String = ""

    override fun getNewVector(name: String, fieldType: FieldType, allocator: BufferAllocator): FieldVector =
        TimestampTzRangeVector(name, allocator, fieldType)
}
