package xtdb.raft

import com.google.protobuf.ByteString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.moveTo

interface RaftStore {
    val currentTerm: Term
    val votedFor: NodeId?

    fun setTerm(term: Term, votedFor: NodeId?)

    class InMemory(override var currentTerm: Term = 0) : RaftStore {

        override var votedFor: NodeId? = null

        override fun setTerm(term: Term, votedFor: NodeId?) {
            this.currentTerm = term
            this.votedFor = votedFor
        }
    }

    class DiskStore(dir: Path) : RaftStore {
        private val metaFile: Path = dir.resolve("meta")
        private val tempMetaFile = dir.resolve("meta.tmp")

        override var currentTerm: Term
        override var votedFor: NodeId?

        private fun writeMetaFile(path: Path, currentTerm: Term, votedFor: NodeId?) {
            DataOutputStream(Files.newOutputStream(path, CREATE, WRITE, TRUNCATE_EXISTING)).use { out ->
                out.writeLong(currentTerm)
                if (votedFor != null) {
                    out.write(votedFor.toByteArray())
                } else {
                    out.writeLong(0)
                    out.writeLong(0)
                }
            }
        }

        init {
            dir.createDirectories()

            if (metaFile.exists()) {
                DataInputStream(Files.newInputStream(metaFile)).use {
                    currentTerm = it.readLong()
                    val msb = it.readLong()
                    val lsb = it.readLong()
                    votedFor = if (msb == 0L && lsb == 0L) null else UUID(msb, lsb).asNodeId
                }
            } else {
                currentTerm = 0
                votedFor = null
                writeMetaFile(metaFile, currentTerm, votedFor)
            }
        }

        override fun setTerm(term: Term, votedFor: NodeId?) {
            writeMetaFile(tempMetaFile, term, votedFor)
            tempMetaFile.moveTo(metaFile, REPLACE_EXISTING, ATOMIC_MOVE)
            this.currentTerm = term
            this.votedFor = votedFor
        }

    }
}