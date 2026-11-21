package xtdb.index

import xtdb.api.TableRef
import xtdb.api.error.Fault
import xtdb.block.proto.SecondaryIndexEntry
import xtdb.block.proto.buildingIndex
import xtdb.block.proto.droppedIndex
import xtdb.block.proto.secondaryIndexEntry
import xtdb.log.proto.CreateIndex
import xtdb.log.proto.createIndex
import xtdb.time.InstantUtil.asMicros
import xtdb.time.InstantUtil.fromMicros
import xtdb.trie.BlockIndex
import xtdb.trie.ColumnName
import java.time.Instant
import java.util.UUID

/**
 * A declared index: what `CREATE INDEX` names.
 *
 * [id] is fixed when the declaration is submitted and travels the logs with it, so every node holds the same one,
 * and an index dropped and re-declared under the same [name] is a different index.
 */
data class IndexDef(val id: UUID, val name: String, val table: TableRef, val column: ColumnName) {
    fun toProto(): CreateIndex = createIndex {
        indexId = id.toString()
        indexName = name
        schemaName = table.schemaName
        tableName = table.tableName
        columnName = column
    }

    companion object {
        fun fromProto(proto: CreateIndex) =
            IndexDef(UUID.fromString(proto.indexId), proto.indexName, TableRef(proto.schemaName, proto.tableName), proto.columnName)
    }
}

sealed interface IndexState {
    /** Declared, and not yet usable by a scan. */
    data object Building : IndexState

    /**
     * Dropped by the transaction at [asOf]: out of the name set, and never used by a scan from then on.
     * The index stays in the catalog until its files are collected.
     */
    data class Dropped(val asOf: Instant) : IndexState
}

/**
 * [firstFlushedBlock] is the block that was open when the declaration applied: its flush, and every later one,
 * covers this index, and the blocks below it are the ones a backfill has to cover.
 */
data class SecondaryIndex(val def: IndexDef, val firstFlushedBlock: BlockIndex, val state: IndexState) {
    fun toProto(): SecondaryIndexEntry = secondaryIndexEntry {
        definition = def.toProto()
        firstFlushedBlock = this@SecondaryIndex.firstFlushedBlock
        when (state) {
            IndexState.Building -> building = buildingIndex { }
            is IndexState.Dropped -> dropped = droppedIndex { asOfMicros = state.asOf.asMicros }
        }
    }

    companion object {
        fun fromProto(proto: SecondaryIndexEntry) =
            SecondaryIndex(
                IndexDef.fromProto(proto.definition),
                proto.firstFlushedBlock,
                when (proto.stateCase) {
                    SecondaryIndexEntry.StateCase.BUILDING -> IndexState.Building
                    SecondaryIndexEntry.StateCase.DROPPED -> IndexState.Dropped(fromMicros(proto.dropped.asOfMicros))
                    else -> throw Fault(
                        "secondary index ${proto.definition.indexId} has no state in its block",
                        "xtdb.index/missing-index-state",
                        mapOf("index-id" to proto.definition.indexId),
                    )
                },
            )
    }
}

/** One partition's secondary indices, as one value. */
class IndexCatalog private constructor(private val byId: Map<UUID, SecondaryIndex>) {

    val indices: Collection<SecondaryIndex> get() = byId.values

    /** Whether the catalog holds [id], in any state, dropped included. */
    operator fun contains(id: UUID): Boolean = id in byId

    /** The index declared under [name] and not since dropped — at most one. */
    fun declared(name: String): SecondaryIndex? =
        indices.firstOrNull { it.def.name == name && it.state !is IndexState.Dropped }

    /** [openBlock] is the block the live index is filling as the declaration applies. */
    fun withIndex(def: IndexDef, openBlock: BlockIndex): IndexCatalog {
        if (def.id in byId)
            throw Fault(
                "index ${def.id} is already in the catalog", "xtdb.index/index-id-exists",
                mapOf("index-id" to def.id, "index-name" to def.name),
            )

        declared(def.name)?.let {
            throw Fault(
                "index '${def.name}' is already declared, as ${it.def.id}", "xtdb.index/index-name-declared",
                mapOf("index-name" to def.name, "index-id" to def.id, "declared-index-id" to it.def.id),
            )
        }

        return IndexCatalog(byId + (def.id to SecondaryIndex(def, openBlock, IndexState.Building)))
    }

    fun dropped(id: UUID, asOf: Instant): IndexCatalog {
        val index = byId[id]
            ?: throw Fault("index $id is not in the catalog", "xtdb.index/unknown-index", mapOf("index-id" to id))

        if (index.state is IndexState.Dropped)
            throw Fault(
                "index ${index.def.name} ($id) is already dropped", "xtdb.index/index-already-dropped",
                mapOf("index-id" to id, "index-name" to index.def.name),
            )

        return IndexCatalog(byId + (id to index.copy(state = IndexState.Dropped(asOf))))
    }

    fun toProto(): List<SecondaryIndexEntry> = indices.sortedBy { it.def.id.toString() }.map { it.toProto() }

    companion object {
        @JvmField
        val EMPTY = IndexCatalog(emptyMap())

        @JvmStatic
        fun fromProto(entries: Iterable<SecondaryIndexEntry>) =
            IndexCatalog(entries.map { SecondaryIndex.fromProto(it) }.associateBy { it.def.id })
    }
}
