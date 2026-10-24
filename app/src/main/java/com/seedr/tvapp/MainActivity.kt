package com.seedr.tvapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView
    private var token: String? = null
    private var pollingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val prefs = getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
        token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            startDeviceFlow()
        } else {
            loadFiles()
        }
    }

    private fun startDeviceFlow() {
        lifecycleScope.launch {
            try {
                statusText.text = "Requesting device code..."
                val resp = SeedrApi.requestDeviceCode()
                statusText.text = "Authorize this device:\n1. Open ${resp.verificationUrl}\n2. Enter code: ${resp.userCode}"

                startPolling(resp.deviceCode, resp.interval)
            } catch (e: Exception) {
                statusText.text = "Connection Error: ${e.localizedMessage}"
            }
        }
    }

    private fun startPolling(deviceCode: String, intervalSeconds: Long) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                try {
                    val receivedToken = SeedrApi.pollToken(deviceCode)
                    if (!receivedToken.isNullOrEmpty()) {
                        token = receivedToken
                        getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("auth_token", receivedToken)
                            .apply()

                        statusText.text = "Authenticated successfully!"
                        loadFiles()
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun loadFiles(folderId: String? = null) {
        val currentToken = token ?: return
        lifecycleScope.launch {
            try {
                statusText.text = "Fetching library..."
                val items = SeedrApi.listFolder(currentToken, folderId)
                statusText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = MediaAdapter(items) { item ->
                    if (item.isFolder) {
                        loadFiles(item.id)
                    } else {
                        playVideo(item.id, item.name)
                    }
                }
            } catch (e: Exception) {
                statusText.visibility = View.VISIBLE
                statusText.text = "Failed to load library: ${e.localizedMessage}"
            }
        }
    }

    private fun playVideo(fileId: String, fileName: String) {
        val currentToken = token ?: return
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Resolving stream...", Toast.LENGTH_SHORT).show()
                val streamUrl = SeedrApi.resolveStreamUrl(currentToken, fileId)
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra("STREAM_URL", streamUrl)
                    putExtra("TITLE", fileName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Stream error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }
}

class MediaAdapter(
    private val items: List<SeedrItem>,
    private val onClick: (SeedrItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(android.R.id.text1)
        val subtitle: TextView = v.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = if (item.isFolder) "📁 ${item.name}" else "🎬 ${item.name}"
        holder.subtitle.text = if (item.isFolder) "Folder" else "${item.size / (1024 * 1024)} MB"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
