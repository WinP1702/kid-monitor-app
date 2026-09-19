package com.parentalcontrol.parentview

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Parent dashboard.
 *
 * Supports multiple kid devices — each with its OWN unique pairing key.
 * Polls Supabase Postgres (REST) every 15 s to refresh the device list.
 * All signaling (live screen / camera WebRTC) uses Supabase Realtime (WebSocket).
 * Zero Firebase dependencies.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvDevices: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences(PairingActivity.PREFS_NAME, MODE_PRIVATE) }

    /** Map of pairingKey → list of DeviceItems fetched from Supabase */
    private val devicesByKey = mutableMapOf<String, List<DeviceItem>>()

    private var pollJob: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val supabaseUrl get() = BuildConfig.SUPABASE_URL
    private val supabaseKey get() = BuildConfig.SUPABASE_KEY

    private val deviceAdapter = DeviceAdapter(
        onLiveScreen = { deviceId, pairingKey ->
            startActivity(Intent(this, LiveScreenActivity::class.java).apply {
                putExtra("deviceId", deviceId)       // LiveScreenActivity expects camelCase
                putExtra("deviceName", "")
                putExtra("pairingKey", pairingKey)
            })
        },
        onLiveCamera = { deviceId, pairingKey ->
            startActivity(Intent(this, LiveCameraActivity::class.java).apply {
                putExtra("DEVICE_ID", deviceId)
                putExtra("PAIRING_KEY", pairingKey)
            })
        },
        onAppUsage = { deviceId, pairingKey ->
            startActivity(Intent(this, AppUsageActivity::class.java).apply {
                putExtra("deviceId", deviceId)
                putExtra("pairingKey", pairingKey)
            })
        },
        onDelete = { deviceId, deviceName, pairingKey ->
            confirmDelete(deviceId, deviceName, pairingKey)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val keys = PairingActivity.getSavedKeys(prefs)
        if (keys.isEmpty()) {
            startActivity(Intent(this, PairingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        supportActionBar?.title = "👨‍👧 Parent Monitor"

        rvDevices   = findViewById(R.id.rvDevices)
        tvEmpty     = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        startPolling()
    }

    override fun onResume() {
        super.onResume()
        // Refresh immediately on resume
        lifecycleScope.launch { fetchAllKeys() }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    // ─── Supabase polling ──────────────────────────────────────────────
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                fetchAllKeys()
                delay(15_000L) // refresh every 15 seconds
            }
        }
    }

    private suspend fun fetchAllKeys() {
        val keys = PairingActivity.getSavedKeys(prefs)
        keys.forEach { key -> fetchDevicesForKey(key) }
    }

    /** Fetch all devices for one pairing key from Supabase REST */
    private suspend fun fetchDevicesForKey(pairingKey: String) = withContext(Dispatchers.IO) {
        try {
            val url = "$supabaseUrl/rest/v1/devices?select=*&pairing_key=eq.$pairingKey"
            val request = Request.Builder()
                .url(url)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Accept", "application/json")
                .get()
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext
                val body = resp.body?.string() ?: return@withContext
                val arr = JSONArray(body)
                val devices = mutableListOf<DeviceItem>()

                for (i in 0 until arr.length()) {
                    val obj          = arr.getJSONObject(i)
                    val deviceId     = obj.optString("id")
                    val model        = obj.optString("model", "Unknown")
                    val manufacturer = obj.optString("manufacturer", "")
                    val lastSeen     = obj.optLong("last_seen", 0L)
                    val hasLiveFrame = false // checked via live_frames table; simplified here

                    devices.add(
                        DeviceItem(
                            deviceId   = deviceId,
                            deviceName = "$manufacturer $model".trim(),
                            lastSeenMs = lastSeen,
                            hasLiveFrame = hasLiveFrame,
                            pairingKey = pairingKey
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    devicesByKey[pairingKey] = devices
                    progressBar.visibility = View.GONE
                    rebuildList()
                }
            }
        } catch (e: Exception) {
            // Silently fail — will retry in 15s
        }
    }

    /** Merge all per-key device lists, sort by last seen, update adapter */
    private fun rebuildList() {
        val all = devicesByKey.values.flatten().sortedByDescending { it.lastSeenMs }
        if (all.isEmpty()) {
            val keys = PairingActivity.getSavedKeys(prefs)
            tvEmpty.text = "No devices found.\n\nMake sure the Kid Monitor app is running on the child's phone.\n\nPaired keys: ${keys.size}"
            tvEmpty.visibility   = View.VISIBLE
            rvDevices.visibility = View.GONE
        } else {
            tvEmpty.visibility   = View.GONE
            rvDevices.visibility = View.VISIBLE
            deviceAdapter.submitList(all)
        }
    }

    // ─── Delete device from Supabase ───────────────────────────────────
    private fun confirmDelete(deviceId: String, deviceName: String, pairingKey: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove Device")
            .setMessage("Remove \"$deviceName\" from the list?\n\nThis deletes all its data. The Kid Monitor app will re-register next time it runs.")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // DELETE /rest/v1/devices?id=eq.{deviceId}
                        val request = Request.Builder()
                            .url("$supabaseUrl/rest/v1/devices?id=eq.$deviceId")
                            .header("apikey", supabaseKey)
                            .header("Authorization", "Bearer $supabaseKey")
                            .delete()
                            .build()
                        http.newCall(request).execute().use { resp ->
                            withContext(Dispatchers.Main) {
                                if (resp.isSuccessful) {
                                    Toast.makeText(this@MainActivity, "✅ Device removed", Toast.LENGTH_SHORT).show()
                                    devicesByKey[pairingKey] = devicesByKey[pairingKey]
                                        ?.filter { it.deviceId != deviceId } ?: emptyList()
                                    rebuildList()
                                } else {
                                    Toast.makeText(this@MainActivity, "❌ Failed: ${resp.code}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Options Menu ──────────────────────────────────────────────────
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_ADD_KID, Menu.NONE, "➕ Add Kid Device")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_MANAGE, Menu.NONE, "🔑 Manage Paired Devices")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ADD_KID -> { showAddKeyDialog(); true }
            MENU_MANAGE  -> { showManageDialog(); true }
            else         -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Add a new kid key ─────────────────────────────────────────────
    private fun showAddKeyDialog() {
        val input = EditText(this).apply {
            hint = "8-character pairing key"
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            gravity = Gravity.CENTER
            textSize = 22f
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("➕ Add Kid Device")
            .setMessage("Enter the 8-character key shown on the kid's phone after setup.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val key = input.text.toString().trim().uppercase()
                if (key.length == 8 && key.all { it.isLetterOrDigit() }) {
                    val keys = PairingActivity.getSavedKeys(prefs).toMutableSet()
                    if (keys.contains(key)) {
                        Toast.makeText(this, "This device is already added", Toast.LENGTH_SHORT).show()
                    } else {
                        keys.add(key)
                        PairingActivity.saveKeys(prefs, keys)
                        lifecycleScope.launch { fetchDevicesForKey(key) }
                        Toast.makeText(this, "✅ Device added!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Key must be exactly 8 letters/numbers", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Manage / remove paired keys ──────────────────────────────────
    private fun showManageDialog() {
        val keys = PairingActivity.getSavedKeys(prefs).toList()
        if (keys.isEmpty()) {
            Toast.makeText(this, "No paired devices", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = keys.map { key ->
            val count = devicesByKey[key]?.size ?: 0
            "$key  ($count device${if (count == 1) "" else "s"})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("🔑 Paired Keys")
            .setItems(labels) { _, idx ->
                val key = keys[idx]
                AlertDialog.Builder(this)
                    .setTitle("Remove key: $key?")
                    .setMessage("This will stop monitoring all devices paired with this key. The kid's data will remain on the server.")
                    .setPositiveButton("Remove") { _, _ ->
                        val updated = PairingActivity.getSavedKeys(prefs).toMutableSet()
                        updated.remove(key)
                        PairingActivity.saveKeys(prefs, updated)
                        devicesByKey.remove(key)
                        rebuildList()
                        Toast.makeText(this, "Key removed", Toast.LENGTH_SHORT).show()

                        if (updated.isEmpty()) {
                            startActivity(Intent(this, PairingActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            finish()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    companion object {
        private const val MENU_ADD_KID = 1001
        private const val MENU_MANAGE  = 1002
    }
}

// ─── Data Model ───────────────────────────────────────────────────────────────
data class DeviceItem(
    val deviceId: String,
    val deviceName: String,
    val lastSeenMs: Long,
    val hasLiveFrame: Boolean,
    val pairingKey: String
)

// ─── Adapter ──────────────────────────────────────────────────────────────────
class DeviceAdapter(
    private val onLiveScreen: (String, String) -> Unit,
    private val onLiveCamera: (String, String) -> Unit,
    private val onAppUsage:   (String, String) -> Unit,
    private val onDelete:     (String, String, String) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private var items = listOf<DeviceItem>()

    fun submitList(list: List<DeviceItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:        TextView = view.findViewById(R.id.tvDeviceName)
        val tvLastSeen:    TextView = view.findViewById(R.id.tvLastSeen)
        val tvScreenshots: TextView = view.findViewById(R.id.tvScreenshotCount)
        val tvStatus:      TextView = view.findViewById(R.id.tvStatus)
        val btnLive:       Button   = view.findViewById(R.id.btnLiveScreen)
        val btnCamera:     Button   = view.findViewById(R.id.btnLiveCamera)
        val btnUsage:      Button   = view.findViewById(R.id.btnAppUsage)
        val btnDelete:     Button   = view.findViewById(R.id.btnDeleteDevice)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.deviceName

        val ageMs   = System.currentTimeMillis() - item.lastSeenMs
        val ageMins = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(ageMs)
        val isOnline = ageMins < 2

        holder.tvLastSeen.text = when {
            ageMins < 2  -> "🟢 Online now"
            ageMins < 60 -> "🟡 ${ageMins}m ago"
            else         -> "🔴 ${java.util.concurrent.TimeUnit.MILLISECONDS.toHours(ageMs)}h ago"
        }

        holder.tvStatus.text = if (isOnline) "LIVE" else "OFFLINE"
        holder.tvStatus.setTextColor(
            if (isOnline) 0xFF4FC3F7.toInt() else 0xFF888888.toInt()
        )

        holder.tvScreenshots.text = when {
            !item.hasLiveFrame -> "Waiting for first frame..."
            isOnline           -> "Live frames available ✓"
            else               -> "Last frame available (offline)"
        }

        holder.btnLive.setOnClickListener   { onLiveScreen(item.deviceId, item.pairingKey) }
        holder.btnCamera.setOnClickListener { onLiveCamera(item.deviceId, item.pairingKey) }
        holder.btnUsage.setOnClickListener  { onAppUsage(item.deviceId, item.pairingKey) }
        holder.btnDelete.setOnClickListener { onDelete(item.deviceId, item.deviceName, item.pairingKey) }
    }

    override fun getItemCount() = items.size
}
