package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import xtdb.arrow.Relation
import xtdb.util.closeOnCatch
import xtdb.util.deleteOnCatch
import xtdb.util.openWritableChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

internal class Spill(
    private val al: BufferAllocator, private val inRel: Relation,
    val path: Path, private val ch: WritableByteChannel, private val unloader: Relation.RelationUnloader,
) : AutoCloseable {

    var rowCount: Int = 0; private set

    fun spill() {
        if (inRel.rowCount == 0) return
        rowCount += inRel.rowCount
        unloader.writePage()
        inRel.clear()
    }

    fun openDataLoader() = Relation.loader(al, path)

    fun loadAll(outRel: Relation) {
        outRel.clear()
        Relation(al, inRel.schema).use { tmpRel ->
            openDataLoader().use { loader ->
                while (loader.loadNextPage(tmpRel)) {
                    outRel.append(tmpRel)
                }
            }
        }
    }

    fun end() {
        unloader.end()
    }

    override fun close() {
        unloader.close()
        ch.close()
        Files.deleteIfExists(path)
    }

    companion object {
        fun open(al: BufferAllocator, dataRel: Relation): Spill =
            Files.createTempFile("xtdb-build-side-", ".arrow").deleteOnCatch { dataPath ->
                dataPath.openWritableChannel().closeOnCatch { dataCh ->
                    dataRel.startUnload(dataCh).closeOnCatch { dataUnloader ->
                        Spill(al, dataRel, dataPath, dataCh, dataUnloader)
                    }
                }
            }
    }
}