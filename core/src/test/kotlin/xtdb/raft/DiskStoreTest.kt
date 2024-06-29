package xtdb.raft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xtdb.raft.RaftStore.DiskStore
import java.nio.file.Files

class DiskStoreTest {
    @Test
    fun testDiskStore() {
        val dir = Files.createTempDirectory("disk-store")

        val store1 = DiskStore(dir)
        assertEquals(0, store1.currentTerm)
        assertNull(store1.votedFor)

        val votedFor = NodeId.randomUUID()
        store1.setTerm(1, votedFor)
        assertEquals(1, store1.currentTerm)
        assertEquals(votedFor, store1.votedFor)

        val store2 = DiskStore(dir)
        assertEquals(1, store2.currentTerm)
        assertEquals(votedFor, store2.votedFor)

        store2.setTerm(2, null)
        assertEquals(2, store2.currentTerm)
        assertNull(store2.votedFor)

        val store3 = DiskStore(dir)
        assertEquals(2, store3.currentTerm)
        assertNull(store3.votedFor)
    }

}