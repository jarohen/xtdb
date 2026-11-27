package xtdb.arrow

import org.apache.arrow.flatbuf.RecordBatch
import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.vector.TypeLayout
import org.apache.arrow.vector.ipc.message.ArrowFieldNode
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.Schema

/**
 * Which fields of a file's schema a reader wants — a field the predicate rejects is dropped with its whole subtree.
 * Record batches carry nodes and buffers in depth-first schema order, so the rejected positions are known up front
 * and never need materialising.
 */
class PageProjection(fullSchema: Schema, keep: (List<String>) -> Boolean) {
    private val nodeKeep = ArrayList<Boolean>()
    private val bufKeep = ArrayList<Boolean>()

    val schema: Schema

    init {
        fun walk(f: Field, path: List<String>, parentKept: Boolean): Field? {
            val kept = parentKept && keep(path)
            nodeKeep.add(kept)
            repeat(TypeLayout.getTypeLayout(f.type).bufferLayouts.size) { bufKeep.add(kept) }

            val children = f.children.mapNotNull { walk(it, path + it.name, kept) }
            return if (kept) Field(f.name, f.fieldType, children) else null
        }

        schema = Schema(fullSchema.fields.mapNotNull { walk(it, listOf(it.name), true) }, fullSchema.customMetadata)
    }

    private val nodeKeepA = nodeKeep.toBooleanArray()
    private val bufKeepA = bufKeep.toBooleanArray()

    /** Filters an already-deserialised batch, releasing the dropped buffers. */
    fun filter(rb: ArrowRecordBatch): ArrowRecordBatch =
        rb.use {
            ArrowRecordBatch(
                rb.length,
                rb.nodes.filterIndexed { i, _ -> nodeKeepA[i] },
                rb.buffers.filterIndexed { i, _ -> bufKeepA[i] }
            )
        }

    /** Builds a batch straight from the flatbuf, slicing only the buffers we keep. Takes over one retain on [body]. */
    fun deserialize(fb: RecordBatch, body: ArrowBuf): ArrowRecordBatch {
        check(fb.compression() == null) { "compressed batches unsupported" }
        val nodes = ArrayList<ArrowFieldNode>(fb.nodesLength())
        for (i in 0 until fb.nodesLength())
            if (nodeKeepA[i]) fb.nodes(i).let { nodes.add(ArrowFieldNode(it.length(), it.nullCount())) }

        val buffers = ArrayList<ArrowBuf>(fb.buffersLength())
        for (i in 0 until fb.buffersLength())
            if (bufKeepA[i]) fb.buffers(i).let { buffers.add(body.slice(it.offset(), it.length())) }

        return ArrowRecordBatch(fb.length().toInt(), nodes, buffers, org.apache.arrow.vector.compression.NoCompressionCodec.DEFAULT_BODY_COMPRESSION, false).also { body.referenceManager.release() }
    }
}
