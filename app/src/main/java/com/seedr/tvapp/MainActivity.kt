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
                statusText.text = "Requesting code from Seedr..."
                val resp = SeedrApi.requestDeviceCode()
                
                statusText.text = "1. Open: ${resp.verificationUrl}\n2. Enter Code: ${resp.userCode}\n\nWaiting for approval..."
                startPolling(resp.deviceCode, resp.interval, resp.userCode)
            } catch (e: Exception) {
                statusText.text = "Connection Error: ${e.localizedMessage}"
            }
        }
    }

    private fun startPolling(deviceCode: String, intervalSeconds: Long, userCode: String) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            var attempts = 0
            while (isActive) {
                delay(intervalSeconds * 1000L)
                attempts++
                try {
                    val receivedToken = SeedrApi.pollToken(deviceCode)
                    if (!receivedToken.isNullOrEmpty()) {
                        token = receivedToken
                        getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("auth_token", receivedToken)
                            .apply()

                        statusText.text = "Device Authorized! Loading files..."
                        loadFiles()
                        break
                    } else {
                        statusText.text = "1. Open: seedr.cc/devices\n2. Enter Code: $userCode\n\nChecking approval status ($attempts)..."
                    }
                } catch (e: Exception) {
                    // Continue polling even on temporary network drop
                }
            }
        }
    }

    private fun loadFiles(folderId: String? = null) {
        val currentToken = token ?: return
        lifecycleScope.launch {
            try {
                statusText.visibility = View.VISIBLE
                statusText.text = "Loading media files..."
                val items = SeedrApi.listFolder(currentToken, folderId)
                
                if (items.isEmpty()) {
                    statusText.text = "Seedr folder is empty.\nAdd a torrent/magnet link on Seedr.cc to stream."
                    statusText.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    statusText.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = MediaAdapter(items) { item ->
                        if (item.isFolder) {
                            loadFiles(item.id)
                        } else {
                            playVideo(item.id, item.name)
                        }
                    }
                }
            } catch (e: Exception) {
                statusText.visibility = View.VISIBLE
                statusText.text = "Error: ${e.localizedMessage}\n(Token might be expired. Clear app data to re-link)"
            }
        }
    }

    private fun playVideo(fileId: String, fileName: String) {
        val currentToken = token ?: return
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Opening $fileName...", Toast.LENGTH_SHORT).show()
                val streamUrl = SeedrApi.resolveStreamUrl(currentToken, fileId)
                val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                    putExtra("STREAM_URL", streamUrl)
                    putExtra("TITLE", fileName)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Stream Error: ${e.message}", Toast.LENGTH_LONG).show()
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
