package com.offlinemesh.messenger.core.mesh

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Splits attachments into frames that fit a Nearby payload, and reassembles them.
 *
 * Chunk size is deliberately conservative: the frame header plus Signal padding must fit into
 * [PacketCodec.MAX_FRAME_SIZE] and the transport's own 100 KB ceiling.
 */
object FileChunker {

    const val CHUNK_SIZE = 32 * 1024
    const val MAX_FILE_SIZE = 50L * 1024 * 1024

    fun chunkCount(sizeBytes: Long): Int {
        if (sizeBytes <= 0L) return 1
        return ((sizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }

    fun chunkCount(file: File): Int = chunkCount(file.length())

    fun readChunk(file: File, index: Int): ByteArray {
        require(index >= 0) { "negative chunk index" }
        RandomAccessFile(file, "r").use { raf ->
            val offset = index.toLong() * CHUNK_SIZE
            val length = minOf(CHUNK_SIZE.toLong(), (file.length() - offset).coerceAtLeast(0L)).toInt()
            if (length <= 0) return ByteArray(0)
            raf.seek(offset)
            val buffer = ByteArray(length)
            raf.readFully(buffer)
            return buffer
        }
    }

    /** Appends a chunk to the partial file inside [dir]. Idempotent for an already stored index. */
    fun writeChunk(dir: File, index: Int, bytes: ByteArray) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "chunk_$index.bin")
        if (target.exists() && target.length() == bytes.size.toLong()) return
        FileOutputStream(target, false).use { it.write(bytes) }
    }

    /** Joins all chunks in order; returns null when chunks are missing. */
    fun assemble(dir: File, expectedChunks: Int, output: File): File? {
        if (!dir.isDirectory) return null
        val chunks = (0 until expectedChunks).map { File(dir, "chunk_$it.bin") }
        if (chunks.any { !it.isFile }) return null
        output.parentFile?.mkdirs()
        FileOutputStream(output, false).use { out ->
            for (chunk in chunks) {
                FileInputStream(chunk).use { input -> input.copyTo(out) }
            }
        }
        return output
    }

    fun cleanup(dir: File) {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }
}
