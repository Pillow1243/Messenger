package com.offlinemesh.messenger.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val htmlUrl: String,
    val apkUrl: String?
)

class UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/Pillow1243/Messenger/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "OfflineMeshMessenger")
            val text = conn.inputStream.bufferedReader().readText()
            val root = json.parseToJsonElement(text).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: return@withContext null
            val name = root["name"]?.jsonPrimitive?.content ?: tag
            val htmlUrl = root["html_url"]?.jsonPrimitive?.content ?: ""
            var apkUrl: String? = null
            val assets = root["assets"]?.jsonArray
            if (assets != null) {
                for (asset in assets) {
                    val obj = asset.jsonObject
                    val assetName = obj["name"]?.jsonPrimitive?.content ?: ""
                    if (assetName.endsWith(".apk")) {
                        apkUrl = obj["browser_download_url"]?.jsonPrimitive?.content
                        break
                    }
                }
            }
            ReleaseInfo(tagName = tag, name = name, htmlUrl = htmlUrl, apkUrl = apkUrl)
        } catch (e: Exception) {
            null
        }
    }

    fun isNewer(current: String, latest: String): Boolean {
        val c = current.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val l = latest.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val max = maxOf(c.size, l.size)
        for (i in 0 until max) {
            val cv = if (i < c.size) c[i] else 0
            val lv = if (i < l.size) l[i] else 0
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
