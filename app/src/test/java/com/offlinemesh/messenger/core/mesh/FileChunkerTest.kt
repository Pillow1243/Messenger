package com.offlinemesh.messenger.core.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileChunkerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `empty file still counts as one chunk`() {
        assertEquals(1, FileChunker.chunkCount(0))
    }

    @Test
    fun `chunk count rounds up`() {
        assertEquals(1, FileChunker.chunkCount(1))
        assertEquals(1, FileChunker.chunkCount(FileChunker.CHUNK_SIZE.toLong()))
        assertEquals(2, FileChunker.chunkCount(FileChunker.CHUNK_SIZE + 1L))
        assertEquals(3, FileChunker.chunkCount(FileChunker.CHUNK_SIZE * 2L + 5L))
    }

    @Test
    fun `read chunk returns the right slice`() {
        val file = temporaryFolder.newFile("payload.bin")
        val data = ByteArray(FileChunker.CHUNK_SIZE + 10) { (it % 251).toByte() }
        file.writeBytes(data)

        assertArrayEquals(data.copyOfRange(0, FileChunker.CHUNK_SIZE), FileChunker.readChunk(file, 0))
        assertArrayEquals(data.copyOfRange(FileChunker.CHUNK_SIZE, data.size), FileChunker.readChunk(file, 1))
    }

    @Test
    fun `read chunk beyond the end is empty`() {
        val file = temporaryFolder.newFile("short.bin")
        file.writeBytes(ByteArray(4))
        assertEquals(0, FileChunker.readChunk(file, 5).size)
    }

    @Test
    fun `assemble rebuilds the original file`() {
        val source = temporaryFolder.newFile("source.bin")
        val data = ByteArray(FileChunker.CHUNK_SIZE * 3 + 17) { (it * 7 % 256).toByte() }
        source.writeBytes(data)

        val chunkDir = temporaryFolder.newFolder("chunks")
        val total = FileChunker.chunkCount(source)
        for (index in 0 until total) {
            FileChunker.writeChunk(chunkDir, index, FileChunker.readChunk(source, index))
        }

        val output = File(temporaryFolder.root, "assembled.bin")
        val assembled = FileChunker.assemble(chunkDir, total, output)
        assertNotNull(assembled)
        assertArrayEquals(data, assembled!!.readBytes())
    }

    @Test
    fun `assemble fails when a chunk is missing`() {
        val chunkDir = temporaryFolder.newFolder("partial")
        FileChunker.writeChunk(chunkDir, 0, ByteArray(10))
        val output = File(temporaryFolder.root, "out.bin")
        assertNull(FileChunker.assemble(chunkDir, 2, output))
    }

    @Test
    fun `write chunk is idempotent`() {
        val chunkDir = temporaryFolder.newFolder("dup")
        FileChunker.writeChunk(chunkDir, 0, ByteArray(10) { 1 })
        FileChunker.writeChunk(chunkDir, 0, ByteArray(10) { 1 })
        assertEquals(1, chunkDir.listFiles()?.size)
    }
}
