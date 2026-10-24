package com.seedr.tvapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

sealed class PollResult {
    data class Success(val token: String) : PollResult()
    object Pending : PollResult()
    object EmptyToken : PollResult()
    data class Error(val message: String) : PollResult()
}

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
        .followRedirects(true)
        .build()

    private const val CLIENT_ID = "seedr_xbmc"

    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.seedr.cc/api/device/code?client_id=$CLIENT_ID")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $body")
            }
            val json = JSONObject(body)
            DeviceCodeResponse(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUrl = json.optString("verification_url", "https://www.seedr.cc/devices"),
                interval = json.optLong("interval", 5L).coerceAtLeast(3L)
            )
        }
    }

    suspend fun pollToken(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val requestUrl = "https://www.seedr.cc/api/device/authorize?client_id=$CLIENT_ID&device_code=$deviceCode"
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            android.util.Log.d("SeedrApi", "pollToken HTTP ${response.code}: $body")

            if (body.startsWith("{")) {
                val json = JSONObject(body)

                if (json.has("access_token")) {
                    val token = json.getString("access_token")
                    if (token.isNotEmpty()) {
                        return@withContext PollResult.Success(token)
                    } else {
                        return@withContext PollResult.EmptyToken
                    }
                }
                if (json.has("token")) {
                    val token = json.getString("token")
                    if (token.isNotEmpty()) {
                        return@withContext PollResult.Success(token)
                    }
                }

                val error = json.optString("error")
                if (error == "authorization_pending") {
                    return@withContext PollResult.Pending
                } else if (error.isNotEmpty()) {
                    return@withContext PollResult.Error(error)
                }
            }
            if (response.code == 401) {
                return@withContext PollResult.Error("Device code expired or invalid (HTTP 401)")
            }
            PollResult.Error("HTTP ${response.code}")
        }
    }

    suspend fun loginWithPassword(username: String, password: String): String = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", "seedr_chrome")
            .add("type", "login")
            .add("username", username)
            .add("password", password)
            .build()

        val request = Request.Builder()
            .url("https://www.seedr.cc/oauth_test/token.php")
            .post(form)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.startsWith("{")) {
                val json = JSONObject(body)
                if (json.has("access_token")) {
                    val token = json.getString("access_token")
                    if (token.isNotEmpty()) return@withContext token
                }
                val error = json.optString("error_description", json.optString("error", "Login failed (HTTP ${response.code})"))
                throw IOException(error)
            }
            throw IOException("Login failed (HTTP ${response.code})")
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
            if (!response.isSuccessful) {
                throw IOException("Failed to load folder (HTTP ${response.code})")
            }
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
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val request = Request.Builder()
            .url("https://www.seedr.cc/rest/file/$fileId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        noRedirectClient.newCall(request).execute().use { response ->
            val redirectLocation = response.header("Location")
            if (!redirectLocation.isNullOrEmpty()) {
                return@withContext redirectLocation
            }
            val body = response.body?.string().orEmpty()
            if (body.contains("url")) {
                val json = JSONObject(body)
                json.optString("url")
            } else {
                "https://www.seedr.cc/rest/file/$fileId?access_token=$token"
            }
        }
    }
}
