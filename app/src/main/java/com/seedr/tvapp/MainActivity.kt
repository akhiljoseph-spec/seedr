package com.seedr.tvapp

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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

        statusText.setOnClickListener {
            if (token.isNullOrEmpty()) {
                showAuthOptionsDialog()
            }
        }

        val prefs = getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
        token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            startDeviceFlow()
        } else {
            loadFiles()
        }
    }

    private fun startDeviceFlow(forceNew: Boolean = false) {
        val prefs = getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
        val savedDeviceCode = prefs.getString("pending_device_code", null)
        val savedUserCode = prefs.getString("pending_user_code", null)
        val savedExpiresAt = prefs.getLong("pending_expires_at", 0L)

        val now = System.currentTimeMillis()
        if (!forceNew && !savedDeviceCode.isNullOrEmpty() && !savedUserCode.isNullOrEmpty() && savedExpiresAt > now) {
            statusText.text = "1. Open: https://www.seedr.cc/devices\n2. Enter Code: $savedUserCode\n\nWaiting for approval...\n(Tap screen for options)"
            startPolling(savedDeviceCode, 5L, savedUserCode)
            return
        }

        lifecycleScope.launch {
            try {
                statusText.text = "Requesting code from Seedr..."
                val resp = SeedrApi.requestDeviceCode()

                prefs.edit()
                    .putString("pending_device_code", resp.deviceCode)
                    .putString("pending_user_code", resp.userCode)
                    .putLong("pending_expires_at", System.currentTimeMillis() + 15 * 60 * 1000L)
                    .apply()

                statusText.text = "1. Open: ${resp.verificationUrl}\n2. Enter Code: ${resp.userCode}\n\nWaiting for approval...\n(Tap screen for options)"
                startPolling(resp.deviceCode, resp.interval, resp.userCode)
            } catch (e: Exception) {
                statusText.text = "Connection Error: ${e.localizedMessage}\n\n(Tap screen to retry)"
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
                    when (val result = SeedrApi.pollToken(deviceCode)) {
                        is PollResult.Success -> {
                            saveTokenAndLoad(result.token)
                            break
                        }
                        is PollResult.Pending -> {
                            statusText.text = "1. Open: seedr.cc/devices\n2. Enter Code: $userCode\n\nChecking approval status ($attempts)...\n(Tap screen for options)"
                        }
                        is PollResult.EmptyToken -> {
                            statusText.text = "1. Open: seedr.cc/devices\n2. Enter Code: $userCode\n\n⚠️ Approved on web, but Seedr returned an empty token!\nMake sure you entered your Seedr account password correctly when approving on web.\n\n(Tap screen for alternative login methods)"
                            getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .remove("pending_device_code")
                                .apply()
                            break
                        }
                        is PollResult.Error -> {
                            statusText.text = "1. Open: seedr.cc/devices\n2. Enter Code: $userCode\n\nStatus: ${result.message}\n\n(Tap screen to retry or change login)"
                            getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .remove("pending_device_code")
                                .apply()
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Continue polling even on temporary network drop
                }
            }
        }
    }

    private fun saveTokenAndLoad(receivedToken: String) {
        token = receivedToken
        getSharedPreferences("seedr_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("auth_token", receivedToken)
            .remove("pending_device_code")
            .remove("pending_user_code")
            .remove("pending_expires_at")
            .apply()

        statusText.text = "Device Authorized! Loading files..."
        loadFiles()
    }

    private fun showAuthOptionsDialog() {
        val options = arrayOf(
            "Refresh Pairing Code",
            "Login with Email & Password",
            "Paste Token Manually"
        )
        AlertDialog.Builder(this)
            .setTitle("Authentication Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startDeviceFlow(forceNew = true)
                    1 -> showEmailPasswordDialog()
                    2 -> showPasteTokenDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmailPasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val emailInput = EditText(this).apply {
            hint = "Seedr Email"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val passInput = EditText(this).apply {
            hint = "Seedr Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(emailInput)
        layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Seedr Direct Login")
            .setView(layout)
            .setPositiveButton("Log In") { _, _ ->
                val email = emailInput.text.toString().trim()
                val pass = passInput.text.toString().trim()
                if (email.isNotEmpty() && pass.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            statusText.text = "Logging into Seedr..."
                            val tok = SeedrApi.loginWithPassword(email, pass)
                            saveTokenAndLoad(tok)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Login error: ${e.message}", Toast.LENGTH_LONG).show()
                            startDeviceFlow()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteTokenDialog() {
        val input = EditText(this).apply {
            hint = "Paste Bearer or Personal Access Token"
        }
        val container = LinearLayout(this).apply {
            setPadding(50, 40, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Paste Seedr Token")
            .setView(container)
            .setPositiveButton("Save & Connect") { _, _ ->
                val pasted = input.text.toString().trim()
                if (pasted.isNotEmpty()) {
                    saveTokenAndLoad(pasted)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
