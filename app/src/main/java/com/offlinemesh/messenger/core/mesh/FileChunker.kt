package com.offlinemesh.messenger.core.mesh

import java.io.File
import java.io.FileInputStream

object FileChunker {
    const val CHUNK_SIZE = 8192
    const val MAX_FILE_SIZE = 50 * 1024 * 1024

    fun chunkCount(file: File): Int {
        val size = file.length()
        return if (size == 0L) 1 else ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }

    fun readChunk(file: File, index: Int): ByteArray {
        val buffer = ByteArray(CHUNK_SIZE)
        FileInputStream(file).use { input ->
            var skipped = 0L
            val toSkip = index.toLong() * CHUNK_SIZE
            while (skipped < toSkip) {
                val s = input.skip(toSkip - skipped)
                if (s <= 0) break
                skipped += s
            }
            var offset = 0
            while (offset < buffer.size) {
                val r = input.read(buffer, offset, buffer.size - offset)
                if (r == -1) break
                offset += r
            }
            return if (offset == buffer.size) buffer else buffer.copyOf(offset)
        }
    }
}
