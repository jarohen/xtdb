package xtdb.operator.let

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import org.apache.arrow.vector.types.pojo.Schema
import xtdb.ICursor
import xtdb.arrow.RelationReader
import xtdb.util.closeAll
import java.util.function.Consumer

class LetCursor(
    private val al: BufferAllocator,
    private val boundCursor: ICursor<RelationReader>,
    private val toBodyCursor: BodyCursorFactory
) : ICursor<RelationReader> {

    class BoundBatch(internal val schema: Schema, internal val recordBatch: ArrowRecordBatch) : AutoCloseable {
        override fun close() = recordBatch.close()
    }

    @FunctionalInterface
    fun interface BodyCursorFactory {
        // NOTE: doesn't own the batches - LetCursor will close them.
        fun open(batches: List<BoundBatch>): ICursor<RelationReader>
    }

    private val boundBatches = mutableListOf<BoundBatch>()

    private val bodyCursorDelegate = lazy {
        boundCursor.forEachRemaining { rel ->
            rel.openDirectSlice(al).use { relSlice ->
                boundBatches += BoundBatch(relSlice.schema, relSlice.openArrowRecordBatch())
            }
        }

        toBodyCursor.open(boundBatches)
    }

    private val bodyCursor: ICursor<RelationReader> by bodyCursorDelegate

    override fun tryAdvance(action: Consumer<in RelationReader>) = bodyCursor.tryAdvance(action)

    override fun estimateSize() = bodyCursor.estimateSize()
    override fun getExactSizeIfKnown() = bodyCursor.exactSizeIfKnown
    override fun characteristics() = bodyCursor.characteristics()
    override fun hasCharacteristics(characteristics: Int) = bodyCursor.hasCharacteristics(characteristics)

    override fun close() {
        if (bodyCursorDelegate.isInitialized()) bodyCursor.close()

        boundBatches.closeAll()
    }
}