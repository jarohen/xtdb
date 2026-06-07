package xtdb.authz

import clojure.lang.PersistentArrayMap
import xtdb.database.DatabaseName
import xtdb.database.DatabaseState
import xtdb.database.DatabaseStorage
import xtdb.indexer.DatabaseSnapshot
import xtdb.kw
import xtdb.query.IQuerySource
import xtdb.query.QueryOpts
import xtdb.table.TableRef
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Node-level cache of the resolved within-XT role membership (`user → roles`), derived from the
 * primary database's `xt/role_membership` table.
 *
 * Always reflects the node watermark, never a query basis: it's rebuilt from the table when the
 * primary database opens (before its log processors start, so log replay strictly follows the
 * scan), then maintained incrementally as `GRANT`/`REVOKE` are processed from the log — the same
 * convergence path on leaders and followers.
 */
class AuthzCatalog {

    private val memberships = AtomicReference<Map<String, Set<String>>>(emptyMap())

    fun grant(user: String, role: String) {
        memberships.updateAndGet { m -> m + (user to (m[user].orEmpty() + role)) }
    }

    fun revoke(user: String, role: String) {
        memberships.updateAndGet { m ->
            val roles = m[user].orEmpty() - role
            if (roles.isEmpty()) m - user else m + (user to roles)
        }
    }

    fun rolesFor(user: String): Set<String> = memberships.get()[user].orEmpty()

    val allMemberships: Map<String, Set<String>> get() = memberships.get()

    val allRoles: Set<String> get() = memberships.get().values.flatten().toSet()

    /**
     * Rebuild the resolved membership by scanning `xt/role_membership` as it stands in [state] —
     * persisted blocks only, since this runs before log replay starts.
     */
    fun rebuildFrom(querySource: IQuerySource, dbStorage: DatabaseStorage, dbState: DatabaseState) {
        // The table catalog is rebuilt from block metadata before this runs, so a miss means no
        // block holds membership rows — nothing to scan, and any rows still on the log are replayed
        // through the processors anyway. Also avoids an unknown-table planner warning (and its
        // `query.warning` metric tick) on every fresh-node startup.
        if (dbState.tableCatalog.getTypes(TableRef(dbState.name, "xt", "role_membership")) == null) return

        val queryDb = object : IQuerySource.QueryDatabase {
            override val storage get() = dbStorage
            override val queryState get() = dbState
            override fun openSnapshot() = DatabaseSnapshot(listOf(dbState.liveIndex.openSnapshot()))
        }
        val queryCatalog = object : IQuerySource.QueryCatalog {
            override val databaseNames: Collection<DatabaseName> = setOf(dbState.name)
            override fun databaseOrNull(dbName: DatabaseName) = queryDb.takeIf { dbName == dbState.name }
        }

        val sql = "SELECT \"user\", \"role\" FROM xt.role_membership"
        val now = Instant.now()

        val prepareOpts = PersistentArrayMap.create(mapOf(
            "current-time".kw to now,
            "default-db".kw to dbState.name,
            "query-text".kw to sql,
        ))

        val rebuilt = HashMap<String, MutableSet<String>>()

        querySource.prepareQuery(sql, queryCatalog, prepareOpts)
            .openQuery(null, QueryOpts(currentTime = now)).use { cursor ->
                cursor.forEachRemaining { rel ->
                    val userVec = rel.vectorFor("user")
                    val roleVec = rel.vectorFor("role")
                    repeat(rel.rowCount) { idx ->
                        rebuilt.getOrPut(userVec.getObject(idx) as String) { HashSet() }
                            .add(roleVec.getObject(idx) as String)
                    }
                }
            }

        memberships.set(rebuilt)
    }

    companion object {
        private val messageDigest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        /**
         * The `_iid` for a membership row, derived from the logical key `(user, role)` — not random —
         * so successive `GRANT`/`REVOKE` on the same pair supersede each other (last-write-wins over
         * system time). Length-prefixing the user keeps the encoding injective.
         */
        @JvmStatic
        fun membershipIid(user: String, role: String): ByteArray {
            val userBytes = user.toByteArray()
            val roleBytes = role.toByteArray()
            val buf = ByteBuffer.allocate(Int.SIZE_BYTES + userBytes.size + roleBytes.size)
                .putInt(userBytes.size).put(userBytes).put(roleBytes)
            return messageDigest.get().digest(buf.array()).copyOfRange(0, 16)
        }
    }
}
