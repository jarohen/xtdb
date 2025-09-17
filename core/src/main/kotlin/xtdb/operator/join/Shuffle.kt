package xtdb.operator.join

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.pojo.Schema
import xtdb.arrow.Relation
import xtdb.arrow.VectorWriter
import xtdb.expression.map.IndexHasher.Companion.hasher
import xtdb.trie.ColumnName
import xtdb.types.Type
import xtdb.types.Type.Companion.I32
import xtdb.types.Type.Companion.ofType
import xtdb.types.schema
import xtdb.util.deleteOnCatch
import xtdb.util.openWritableChannel
import java.nio.file.Files.createTempFile
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

internal const val HASH_COL_NAME = "xt/join-hash"

internal class Shuffle(private val al: BufferAllocator, val hashFile: Path) : AutoCloseable {

    fun openHashLoader() = Relation.loader(al, hashFile)

    // temporary function to load everything back in
    fun loadAll(hashCol: VectorWriter) {
        openHashLoader().use { loader ->
            Relation(al, loader.schema).use { inRel ->
                val inCol = inRel[HASH_COL_NAME]
                while (loader.loadNextPage(inRel))
                    hashCol.append(inCol)
            }
        }
    }

    override fun close() {
        hashFile.deleteIfExists()
    }

    companion object {
        fun open(al: BufferAllocator, hashColNames: List<ColumnName>, dataLoader: Relation.Loader): Shuffle =
            createTempFile("xtdb-build-side-shuffle-hash-", ".arrow").deleteOnCatch { hashFile ->
                Relation(al, dataLoader.schema).use { dataRel ->
                    val hasher = dataRel.hasher(hashColNames)

                    // step 1 - just hashes
                    Relation(al, schema(HASH_COL_NAME ofType I32)).use { hashRel ->
                        val hashCol = hashRel[HASH_COL_NAME]
                        hashFile.openWritableChannel().use { outCh ->
                            hashRel.startUnload(outCh).use { unloader ->
                                while (dataLoader.loadNextPage(dataRel)) {
                                    hasher.writeAllHashes(hashCol)
                                    unloader.writePage()
                                }

                                unloader.end()
                            }
                        }
                    }

                    Shuffle(al, hashFile)
                }
            }
    }
}