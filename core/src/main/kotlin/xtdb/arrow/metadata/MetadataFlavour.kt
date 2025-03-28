package xtdb.arrow.metadata

import xtdb.arrow.VectorReader
import xtdb.vector.IVectorReader
import java.nio.ByteBuffer

sealed interface MetadataFlavour {
    val valueCount: Int
    val nullable: Boolean
    fun isNull(idx: Int): Boolean

    sealed interface Numeric : MetadataFlavour {
        fun getMetaDouble(idx: Int): Double
    }

    interface Number : Numeric
    interface DateTime : Numeric
    interface Duration : Numeric
    interface TimeOfDay : Numeric

    interface Bytes : MetadataFlavour {
        fun getBytes(idx: Int): ByteBuffer
    }

    interface List : MetadataFlavour {
        val listElements: VectorReader
    }

    interface Set : MetadataFlavour {
        val listElements: VectorReader
    }

    interface Struct : MetadataFlavour {
        val vectors: Iterable<VectorReader>
    }
}