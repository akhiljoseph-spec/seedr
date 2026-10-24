package com.seedr.tvapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val interval: Long
)

data class SeedrItem(
    val id: String,
    val name: String,
    val size: Long,
    val isFolder: Boolean
)

object SeedrApi {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .build()

    private const val CLIENT_ID = "seedr_xbmc"

    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        // Correct endpoint: /api/device/code instead of /oauth/device/code
        val request = Request.Builder()
            .url("https://www.seedr.cc/api/device/code?client_id=$CLIENT_ID")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Server returned HTTP ${response.code}: $body")
            }
            val json = JSONObject(body)
            if (!json.has("device_code")) {
                val errorMsg = json.optString("error_description", json.optString("error", "Missing device_code"))
                throw IOException(errorMsg)
            }
            DeviceCodeResponse(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUrl = json.optString("verification_url", "https://www.seedr.cc/devices"),
                interval = json.optLong("interval", 5L)
            )
        }
    }

    suspend fun pollToken(deviceCode: String): String? = withContext(Dispatchers.IO) {
        // Polling uses GET to /api/device/authorize
        val request = Request.Builder()
            .url("https://www.seedr.cc/api/device/authorize?client_id=$CLIENT_ID&device_code=$deviceCode")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)

            if (json.has("access_token")) {
                json.getString("access_token")
            } else {
                null
            }
        }
    }

    suspend fun listFolder(token: String, folderId: String? = null): List<SeedrItem> = withContext(Dispatchers.IO) {
        val url = if (folderId.isNullOrEmpty()) {
            "https://www.seedr.cc/rest/folder"
        } else {
            "https://www.seedr.cc/rest/folder/$folderId"
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val results = mutableListOf<SeedrItem>()

            val foldersArr = json.optJSONArray("folders")
            if (foldersArr != null) {
                for (i in 0 until foldersArr.length()) {
                    val f = foldersArr.getJSONObject(i)
                    results.add(
                        SeedrItem(
                            id = f.optString("id"),
                            name = f.optString("name"),
                            size = f.optLong("size", 0L),
                            isFolder = true
                        )
                    )
                }
            }

            val filesArr = json.optJSONArray("files")
            if (filesArr != null) {
                for (i in 0 until filesArr.length()) {
                    val f = filesArr.getJSONObject(i)
                    results.add(
                        SeedrItem(
                            id = f.optString("folder_file_id", f.optString("id")),
                            name = f.optString("name"),
                            size = f.optLong("size", 0L),
                            isFolder = false
                        )
                    )
                }
            }
            results
        }
    }

    suspend fun resolveStreamUrl(token: String, fileId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.seedr.cc/rest/file/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val redirectLocation = response.header("Location")
            if (!redirectLocation.isNullOrEmpty()) {
                return@withContext redirectLocation
            }
            val body = response.body?.string().orEmpty()
            if (body.contains("\"url\"")) {
                val json = JSONObject(body)
                json.optString("url")
            } else {
                "https://www.seedr.cc/rest/file/$fileId?access_token=$token"
            }
        }
    }
}
