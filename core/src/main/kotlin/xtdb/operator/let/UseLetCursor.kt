package xtdb.operator.let

import org.apache.arrow.memory.BufferAllocator
import xtdb.ICursor
import xtdb.arrow.Relation
import xtdb.arrow.RelationReader
import java.util.function.Consumer

class UseLetCursor(
    private val al: BufferAllocator, boundBatches: Iterable<LetCursor.BoundBatch>
) : ICursor<RelationReader> {

    private val batches = boundBatches.spliterator()

    override fun tryAdvance(action: Consumer<in RelationReader>): Boolean =
        batches.tryAdvance { batch ->
            Relation(al, batch.schema).use { rel ->
                rel.load(batch.recordBatch)

                // TODO: don't need all this openAsRoot dance when the operators all use xtdb.arrow
                rel.openAsRoot(al).use { root ->
                    action.accept(RelationReader.from(root))
                }
            }
        }
}