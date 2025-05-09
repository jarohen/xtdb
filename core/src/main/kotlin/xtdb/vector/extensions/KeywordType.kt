package xtdb.vector.extensions

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.ExtensionTypeRegistry
import org.apache.arrow.vector.types.pojo.FieldType

object KeywordType : XtExtensionType("xt/clj-keyword", Utf8.INSTANCE) {
    init {
        ExtensionTypeRegistry.register(this)
    }

    override fun deserialize(serializedData: String): ArrowType = this

    override fun getNewVector(name: String, fieldType: FieldType, allocator: BufferAllocator): FieldVector =
        KeywordVector(name, allocator, fieldType)
}
