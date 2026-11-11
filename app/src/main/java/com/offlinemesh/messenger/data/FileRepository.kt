package com.offlinemesh.messenger.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream

class FileRepository(private val context: Context) {

    fun voiceDir(): File = File(context.filesDir, "voice").apply { mkdirs() }
    fun sharedDir(): File = File(context.filesDir, "shared").apply { mkdirs() }
    fun incomingDir(fileId: String): File = File(context.filesDir, "incoming/$fileId").apply { mkdirs() }

    fun saveVoiceBytes(bytes: ByteArray): File {
        val file = File(voiceDir(), "voice_${System.currentTimeMillis()}.m4a")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    fun saveChunk(fileId: String, index: Int, bytes: ByteArray) {
        val dir = incomingDir(fileId)
        val file = File(dir, "chunk_$index.bin")
        FileOutputStream(file).use { it.write(bytes) }
    }

    fun assemble(fileId: String, fileName: String): File {
        val dir = incomingDir(fileId)
        val output = File(sharedDir(), fileName)
        FileOutputStream(output).use { out ->
            val chunks = dir.listFiles()?.filter { it.name.startsWith("chunk_") }
                ?.sortedBy { it.name.removePrefix("chunk_").removeSuffix(".bin").toIntOrNull() ?: 0 }
                ?: emptyList()
            for (chunk in chunks) {
                out.write(chunk.readBytes())
            }
        }
        return output
    }

    fun copyToShared(source: File, fileName: String): File {
        val dest = File(sharedDir(), fileName)
        source.copyTo(dest, overwrite = true)
        return dest
    }
}
