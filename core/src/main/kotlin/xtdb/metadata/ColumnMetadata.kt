package xtdb.metadata

import com.carrotsearch.hppc.IntArrayList
import org.apache.arrow.vector.types.pojo.FieldType.notNullable
import org.apache.arrow.vector.types.pojo.FieldType.nullable
import xtdb.arrow.*
import xtdb.arrow.metadata.MetadataFlavour
import xtdb.arrow.metadata.MetadataFlavour.*
import xtdb.bloom.BloomBuilder
import xtdb.bloom.toByteBuffer
import xtdb.trie.RowIndex

class ColumnMetadata(private val colsVec: VectorWriter) {
    private val colNameVec = colsVec["col-name"]
    private val rootColVec = colsVec["root-col?"]
    private val countVec = colsVec["count"]
    private val typesVec = colsVec["types"]

    private fun writeNumericMetadata(flavours: List<Numeric>, typeName: String) {
        if (flavours.isNotEmpty()) {
            val typeVec = typesVec.vectorFor(typeName, nullable(STRUCT))
            val minVec = typeVec.vectorFor("min", notNullable(F64))
            val maxVec = typeVec.vectorFor("max", notNullable(F64))

            var minValue = Double.POSITIVE_INFINITY
            var maxValue = Double.NEGATIVE_INFINITY

            for (flavour in flavours) {
                repeat(flavour.valueCount) {
                    if (!flavour.isNull(it)) {
                        val value = flavour.getMetaDouble(it)
                        minValue = minValue.coerceAtMost(value)
                        maxValue = maxValue.coerceAtLeast(value)
                    }
                }
            }

            minVec.writeDouble(minValue)
            maxVec.writeDouble(maxValue)
            typeVec.endStruct()
        }
    }

    private fun writeBytesMetadata(flavours: List<Bytes>) {
        if (flavours.isNotEmpty()) {
            val typeVec = typesVec.vectorFor("bytes", nullable(STRUCT))
            val bloomVec = typeVec.vectorFor("bloom", nullable(VAR_BINARY))

            val bloomBuilder = BloomBuilder()
            flavours.forEach { bloomBuilder.add(it) }
            bloomVec.writeBytes(bloomBuilder.build().toByteBuffer())

            typeVec.endStruct()
        }
    }

    private fun writeMetadata(col: VectorReader, rootCol: Boolean): RowIndex {
        val flavours = col.metadataFlavours

        val childIdxs = IntArrayList()

        for (flavour in flavours) {
            when (flavour) {
                is MetadataFlavour.List -> childIdxs.add(writeMetadata(flavour.listElements, false))
                is MetadataFlavour.Set -> childIdxs.add(writeMetadata(flavour.listElements, false))
                is Struct -> flavour.vectors.forEach { writeMetadata(it, false) }
                else -> Unit
            }
        }

        var childIdx = 0

        val bytes = mutableListOf<Bytes>()
        val dateTimes = mutableListOf<Numeric>()
        val durations = mutableListOf<Numeric>()
        val numbers = mutableListOf<Numeric>()
        val times = mutableListOf<Numeric>()

        for (flavour in flavours) {
            when (flavour) {
                is Bytes -> bytes.add(flavour)

                is MetadataFlavour.List ->
                    typesVec.vectorFor("list", nullable(I32))
                        .writeInt(childIdxs[childIdx++])

                is MetadataFlavour.Set ->
                    typesVec.vectorFor("set", nullable(I32))
                        .writeInt(childIdxs[childIdx++])

                is DateTime -> dateTimes.add(flavour)
                is Duration -> durations.add(flavour)
                is MetadataFlavour.Number -> numbers.add(flavour)
                is TimeOfDay -> times.add(flavour)

                is Struct -> {
                    val keysVec = typesVec.vectorFor("struct", nullable(LIST))
                    val keyVec = keysVec.getListElements(notNullable(I32))

                    repeat(flavour.vectors.count()) { keyVec.writeInt(childIdxs[childIdx++]) }

                    keysVec.endList()
                }
            }
        }

        assert(childIdx == childIdxs.size()) { "haven't used up all the nested vectors" }

        writeNumericMetadata(numbers, "numbers")
        writeNumericMetadata(dateTimes, "date-times")
        writeNumericMetadata(times, "times")
        writeNumericMetadata(durations, "durations")
        writeBytesMetadata(bytes)

        typesVec.endStruct()

        colNameVec.writeObject(col.name)
        rootColVec.writeBoolean(rootCol)
        countVec.writeLong((0 until col.valueCount).count { !col.isNull(it) }.toLong())
        colsVec.endStruct()

        return colsVec.valueCount - 1
    }

    fun writeMetadata(col: VectorReader) = writeMetadata(col, true)
}