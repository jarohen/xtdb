package xtdb.database

import kotlinx.coroutines.runBlocking

/**
 * Blocking bridges onto [Database.Catalog]'s suspending members, for Clojure tests, which can't call a suspend function.
 *
 * Kotlin callers suspend directly instead.
 */
object DatabaseCatalogSync {

    @JvmStatic
    fun attach(dbCatalog: Database.Catalog, dbName: DatabaseName, config: Database.Config?) =
        runBlocking { dbCatalog.attach(dbName, config) }
}
