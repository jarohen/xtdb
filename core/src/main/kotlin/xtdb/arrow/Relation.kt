package xtdb.arrow

import clojure.lang.Keyword
import clojure.lang.Symbol
import org.apache.arrow.flatbuf.MessageHeader
import org.apache.arrow.memory.ArrowBuf
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.compression.CompressionCodec
import org.apache.arrow.vector.compression.CompressionUtil
import org.apache.arrow.vector.compression.CompressionUtil.CodecType
import org.apache.arrow.vector.compression.NoCompressionCodec
import org.apache.arrow.vector.ipc.ReadChannel
import org.apache.arrow.vector.ipc.message.ArrowFieldNode
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import org.apache.arrow.vector.ipc.message.MessageChannelReader
import org.apache.arrow.vector.ipc.message.MessageSerializer
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import xtdb.ArrowWriter
import xtdb.ICursor
import xtdb.arrow.ArrowUnloader.Mode
import xtdb.arrow.ArrowUnloader.Mode.FILE
import xtdb.arrow.ArrowUnloader.Mode.STREAM
import xtdb.arrow.Vector.Companion.openVector
import xtdb.util.*
import java.io.ByteArrayOutputStream
import java.nio.channels.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.*
import java.util.function.Consumer

class Relation(
    private val al: BufferAllocator, val vecs: SequencedMap<String, Vector>, override var rowCount: Int
) : RelationWriter {

    override val schema get() = Schema(vecs.sequencedValues().map { it.field })
    override val vectors get() = vecs.values

    constructor(al: BufferAllocator) : this(al, linkedMapOf<String, Vector>(), 0)

    constructor(al: BufferAllocator, vectors: List<Vector>, rowCount: Int)
            : this(al, vectors.associateByTo(linkedMapOf()) { it.name }, rowCount)

    constructor(al: BufferAllocator, schema: Schema) : this(al, schema.fields)
    constructor(al: BufferAllocator, fields: List<Field>) : this(al, fields.safeMap { it.openVector(al) }, 0)
    constructor(al: BufferAllocator, vararg fields: Field) : this(al, fields.toList())

    override fun vectorForOrNull(name: String) = vecs[name]
    override fun vectorFor(name: String) = vectorForOrNull(name) ?: error("missing vector: $name")
    override operator fun get(name: String) = vectorFor(name)

    override fun vectorFor(name: String, fieldType: FieldType): Vector =
        vecs.compute(name) { _, v ->
            v?.maybePromote(al, fieldType)
                ?: Field(name, fieldType, null).openVector(al)
                    .also { vec -> repeat(rowCount) { vec.writeNull() } }
        }!!

    override fun endRow() {
        rowCount++
        vectors.forEach { vec -> repeat(rowCount - vec.valueCount) { vec.writeNull() } }
    }

    fun loadFromArrow(root: VectorSchemaRoot) {
        vecs.forEach { (name, vec) -> vec.loadFromArrow(root.getVector(name)) }
        rowCount = root.rowCount
    }

    @JvmOverloads
    fun openArrowRecordBatch(codec: CompressionCodec = NoCompressionCodec.INSTANCE): ArrowRecordBatch {
        val nodes = mutableListOf<ArrowFieldNode>()
        val uncompressedBuffers = mutableListOf<ArrowBuf>()

        vecs.values.forEach { it.openUnloadedPage(nodes, uncompressedBuffers) }

        // uncompressed buffers closed here, compressed buffers opened
        val compressedBuffers = uncompressedBuffers.safeMap { buf -> codec.compress(al, buf) }

        // don't retain buffers here - we've opened them.
        return ArrowRecordBatch(
            rowCount, nodes, compressedBuffers,
            CompressionUtil.createBodyCompression(codec), true,
            /* retainBuffers = */ false
        )
    }

    inner class RelationUnloader(
        private val arrowUnloader: ArrowUnloader,
        private val codec: CompressionCodec
    ) : ArrowWriter {

        override fun writePage() {
            try {
                openArrowRecordBatch(codec).use { arrowUnloader.writeBatch(it) }
            } catch (_: ClosedByInterruptException) {
                throw InterruptedException()
            }
        }

        override fun end() = arrowUnloader.end()

        override fun close() = arrowUnloader.close()
    }

    @JvmOverloads
    fun startUnload(ch: WritableByteChannel, mode: Mode = FILE, codec: CompressionCodec = NoCompressionCodec.INSTANCE) =
        RelationUnloader(ArrowUnloader.open(ch, schema, mode), codec)

    @JvmOverloads
    fun startUnload(path: Path, mode: Mode = FILE, codec: CompressionCodec = NoCompressionCodec.INSTANCE) =
        path.openWritableChannel().closeOnCatch { ch -> startUnload(ch, mode, codec) }

    val asArrowStream: ByteArray
        get() {
            val baos = ByteArrayOutputStream()
            startUnload(Channels.newChannel(baos), STREAM).use { unl ->
                unl.writePage()
                unl.end()
            }

            return baos.toByteArray()
        }

    private fun CodecType.createCodec() = CompressionCodec.Factory.INSTANCE.createCodec(this)

    fun load(recordBatch: ArrowRecordBatch) {
        val nodes = recordBatch.nodes.toMutableList()

        val codecType = CodecType.fromCompressionType(recordBatch.bodyCompression.codec)

        if (codecType != CodecType.NO_COMPRESSION) {
            val codec = codecType.createCodec()

            recordBatch.buffers
                // decompress closes input buffer, but we shouldn't, so we retain
                .safeMap { buf -> codec.decompress(al, buf.also { it.referenceManager.retain() }) }

                // decompress then gives us a buffer with a ref-count.
                // loadPage slices, so we close the decompressed buffers at the end
                .useAll { buffers ->
                    val bufQueue = buffers.toMutableList()
                    vecs.values.forEach { it.loadPage(nodes, bufQueue) }
                    require(nodes.isEmpty()) { "Unconsumed nodes: $nodes" }
                    require(bufQueue.isEmpty()) { "Unconsumed buffers: $bufQueue" }
                }

        } else {
            val buffers = recordBatch.buffers.toMutableList()

            vecs.values.forEach { it.loadPage(nodes, buffers) }
            require(nodes.isEmpty()) { "Unconsumed nodes: $nodes" }
            require(buffers.isEmpty()) { "Unconsumed buffers: $buffers" }
        }

        rowCount = recordBatch.length
    }

    interface ILoader : AutoCloseable {
        val schema: Schema
        fun loadNextPage(rel: Relation): Boolean
    }

    class StreamLoader(private val al: BufferAllocator, ch: ReadableByteChannel) : ILoader {

        private val reader = MessageChannelReader(ReadChannel(ch), al)

        override val schema: Schema

        init {
            val schemaMessage = (reader.readNext() ?: error("empty stream")).message
            check(schemaMessage.headerType() == MessageHeader.Schema) { "expected schema message" }

            schema = MessageSerializer.deserializeSchema(schemaMessage)
        }

        override fun loadNextPage(rel: Relation): Boolean {
            val msg = reader.readNext() ?: return false

            msg.message.headerType().let {
                check(it == MessageHeader.RecordBatch) { "unexpected Type message type: $it" }
            }

            val recordBatch = MessageSerializer.deserializeRecordBatch(msg.message, msg.bodyBuffer ?: al.empty)
            try {
                rel.load(recordBatch)
            } finally {
                // If the record batch is compressed, the buffers have already been closed by decompress()
                // So we need to suppress any errors from trying to close them again
                try {
                    recordBatch.close()
                } catch (_: IllegalStateException) {
                    // Ignore "RefCnt has gone negative" errors from compressed buffers
                }
            }

            return true
        }

        override fun close() = reader.close()
    }

    class Loader(private val arrowFileLoader: ArrowFileLoader) : ILoader {

        override val schema: Schema get() = arrowFileLoader.schema
        val pageCount get() = arrowFileLoader.pageCount

        private var lastPageIndex = -1

        fun loadPage(idx: Int, al: BufferAllocator) = Relation(al, schema).closeOnCatch { loadPage(idx, it); it }

        fun loadPage(idx: Int, rel: Relation) {
            val recordBatch = arrowFileLoader.openPage(idx)
            try {
                rel.load(recordBatch)
            } finally {
                // If the record batch is compressed, the buffers have already been closed by decompress()
                // So we need to suppress any errors from trying to close them again
                try {
                    recordBatch.close()
                } catch (_: IllegalStateException) {
                    // Ignore "RefCnt has gone negative" errors from compressed buffers
                }
            }
            lastPageIndex = idx
        }

        override fun loadNextPage(rel: Relation): Boolean {
            if (lastPageIndex + 1 >= pageCount) return false

            loadPage(++lastPageIndex, rel)
            return true
        }

        inner class Cursor(private val rel: Relation, private val closeRel: Boolean) : ICursor {
            private var lastPageIndex = -1

            override val cursorType: String get() = "loader"
            override val childCursors: List<ICursor> get() = emptyList()

            override fun tryAdvance(c: Consumer<in RelationReader>): Boolean {
                if (lastPageIndex + 1 >= pageCount) return false

                loadPage(++lastPageIndex, rel)
                c.accept(rel)
                return true
            }

            override fun close() {
                if (closeRel) rel.close()
            }

        }

        fun openCursor(al: BufferAllocator): ICursor = Cursor(Relation(al, schema), true)
        fun openCursor(al: BufferAllocator, rel: Relation): ICursor = Cursor(rel, false)

        override fun close() = arrowFileLoader.close()
    }

    companion object {

        @JvmStatic
        fun loader(al: BufferAllocator, ch: SeekableByteChannel): Loader =
            Loader(ArrowFileLoader.openFromChannel(al, ch))

        @JvmStatic
        fun loader(al: BufferAllocator, bytes: ByteArray) = loader(al, bytes.asChannel)

        @JvmStatic
        fun loader(al: BufferAllocator, path: Path) = loader(al, Files.newByteChannel(path, READ))

        @JvmStatic
        fun loader(buf: ArrowBuf) = Loader(ArrowFileLoader.openFromArrowBuf(buf))

        @JvmStatic
        fun streamLoader(al: BufferAllocator, path: Path) = StreamLoader(al, path.openReadableChannel())

        @JvmStatic
        fun streamLoader(al: BufferAllocator, bytes: ByteArray) = StreamLoader(al, bytes.asChannel)

        @JvmStatic
        fun fromRoot(al: BufferAllocator, vsr: VectorSchemaRoot) =
            Relation(al, vsr.fieldVectors.map(Vector::fromArrow), vsr.rowCount)

        @JvmStatic
        fun fromRecordBatch(allocator: BufferAllocator, schema: Schema, recordBatch: ArrowRecordBatch): Relation {
            val rel = Relation(allocator, schema)
            // this load retains the buffers
            rel.load(recordBatch)
            return rel
        }

        @JvmStatic
        fun openFromRows(al: BufferAllocator, rows: List<Map<*, *>>): Relation =
            Relation(al).closeOnCatch { rel -> rel.also { for (row in rows) it.writeRow(row) } }

        @JvmStatic
        @Suppress("unused") // used in test-util
        fun openFromCols(al: BufferAllocator, cols: Map<*, List<*>>): Relation =
            cols.entries.safeMap { col ->
                val normalKey = when (val k = col.key) {
                    is String -> k
                    is Symbol -> normalForm(k).toString()
                    is Keyword -> normalForm(k.sym).toString()
                    else -> throw IllegalArgumentException("Column name must be a string, keyword or symbol")
                }

                Vector.fromList(al, normalKey, col.value)
            }.closeAllOnCatch { Relation(al, it, it.firstOrNull()?.valueCount ?: 0) }

        @JvmStatic
        fun openFromArrowStream(al: BufferAllocator, bytes: ByteArray): Relation =
            bytes.asChannel.use { ch ->
                StreamLoader(al, ch).use { loader ->
                    Relation(al, loader.schema).closeOnCatch { rel ->
                        loader.loadNextPage(rel)
                        rel
                    }
                }
            }
    }
}
