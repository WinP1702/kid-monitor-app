package com.parentalcontrol.parentview

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*
import java.util.concurrent.TimeUnit

/**
 * Parent dashboard.
 * Checks pairing key on start — redirects to PairingActivity if not set.
 * Lists only the kid devices registered under the parent's pairing key.
 * Each card: device name, status, [📺 Live Screen] [📊 App Usage] [🗑 Remove]
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvDevices: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences(PairingActivity.PREFS_NAME, MODE_PRIVATE) }

    private var devicesRef: DatabaseReference? = null
    private var devicesListener: ValueEventListener? = null
    private var pairingKey: String = ""

    private val deviceAdapter = DeviceAdapter(
        onLiveScreen = { deviceId ->
            startActivity(Intent(this, LiveScreenActivity::class.java).apply {
                putExtra("deviceId", deviceId)
                putExtra("pairingKey", pairingKey)
            })
        },
        onAppUsage = { deviceId ->
            startActivity(Intent(this, AppUsageActivity::class.java).apply {
                putExtra("deviceId", deviceId)
                putExtra("pairingKey", pairingKey)
            })
        },
        onDelete = { deviceId, deviceName ->
            confirmDelete(deviceId, deviceName)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guard: redirect to pairing if no key saved
        val savedKey = prefs.getString(PairingActivity.KEY_PAIRING_KEY, null)
        if (savedKey.isNullOrBlank()) {
            startActivity(Intent(this, PairingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        pairingKey = savedKey

        setContentView(R.layout.activity_main)
        supportActionBar?.title = "👨‍👧 Parent Monitor"

        rvDevices   = findViewById(R.id.rvDevices)
        tvEmpty     = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        startListeningDevices()
    }

    // ─── Options Menu (Change Key) ─────────────────────────────────────
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_CHANGE_KEY, Menu.NONE, "🔑 Change Pairing Key")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CHANGE_KEY) {
            showChangeKeyDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showChangeKeyDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "New 8-character key"
            filters = arrayOf(
                android.text.InputFilter.AllCaps(),
                android.text.InputFilter.LengthFilter(8)
            )
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            gravity = android.view.Gravity.CENTER
            textSize = 20f
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Change Pairing Key")
            .setMessage("Enter a new 8-character key to connect to a different kid device.")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val newKey = input.text.toString().trim().uppercase()
                if (newKey.length == 8 && newKey.all { it.isLetterOrDigit() }) {
                    prefs.edit().putString(PairingActivity.KEY_PAIRING_KEY, newKey).apply()
                    // Restart activity to reload with new key
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                } else {
                    Toast.makeText(this, "Key must be exactly 8 letters/numbers", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Firebase listener under pairing key namespace ─────────────────
    private fun startListeningDevices() {
        progressBar.visibility = View.VISIBLE
        devicesRef = FirebaseDatabase.getInstance().getReference("users/$pairingKey/devices")

        devicesListener = devicesRef!!.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                progressBar.visibility = View.GONE
                val devices = mutableListOf<DeviceItem>()

                for (child in snapshot.children) {
                    val deviceId     = child.key ?: continue
                    val info         = child.child("info")
                    val model        = info.child("model").getValue(String::class.java) ?: "Unknown Device"
                    val manufacturer = info.child("manufacturer").getValue(String::class.java) ?: ""
                    val lastSeen     = info.child("lastSeen").getValue(Long::class.java) ?: 0L
                    val hasLiveFrame = child.hasChild("liveFrame")

                    devices.add(DeviceItem(deviceId, "$manufacturer $model".trim(), lastSeen, hasLiveFrame))
                }

                // Sort: most recently seen first
                devices.sortByDescending { it.lastSeenMs }

                if (devices.isEmpty()) {
                    tvEmpty.visibility   = View.VISIBLE
                    rvDevices.visibility = View.GONE
                    tvEmpty.text = "No devices found for key: $pairingKey\n\nMake sure the Kid Monitor app is running on the child's phone."
                } else {
                    tvEmpty.visibility   = View.GONE
                    rvDevices.visibility = View.VISIBLE
                    deviceAdapter.submitList(devices)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ─── Delete with confirmation ─────────────────────────────────────
    private fun confirmDelete(deviceId: String, deviceName: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove Device")
            .setMessage("Remove \"$deviceName\" from the list?\n\nThis deletes all its data from the server. The Kid Monitor app on that phone will re-register next time it runs.")
            .setPositiveButton("Remove") { _, _ ->
                devicesRef?.child(deviceId)?.removeValue()
                    ?.addOnSuccessListener {
                        Toast.makeText(this, "✅ Device removed", Toast.LENGTH_SHORT).show()
                    }
                    ?.addOnFailureListener { e ->
                        Toast.makeText(this, "❌ Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        devicesListener?.let { devicesRef?.removeEventListener(it) }
        super.onDestroy()
    }

    companion object {
        private const val MENU_CHANGE_KEY = 1001
    }
}

// ─── Data Model ───────────────────────────────────────────────────────────────
data class DeviceItem(
    val deviceId: String,
    val deviceName: String,
    val lastSeenMs: Long,
    val hasLiveFrame: Boolean
)

// ─── Adapter ──────────────────────────────────────────────────────────────────
class DeviceAdapter(
    private val onLiveScreen: (String) -> Unit,
    private val onAppUsage:   (String) -> Unit,
    private val onDelete:     (String, String) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private var items = listOf<DeviceItem>()

    fun submitList(list: List<DeviceItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:       TextView = view.findViewById(R.id.tvDeviceName)
        val tvLastSeen:   TextView = view.findViewById(R.id.tvLastSeen)
        val tvScreenshots:TextView = view.findViewById(R.id.tvScreenshotCount)
        val tvStatus:     TextView = view.findViewById(R.id.tvStatus)
        val btnLive:      Button   = view.findViewById(R.id.btnLiveScreen)
        val btnUsage:     Button   = view.findViewById(R.id.btnAppUsage)
        val btnDelete:    Button   = view.findViewById(R.id.btnDeleteDevice)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.deviceName

        val ageMs    = System.currentTimeMillis() - item.lastSeenMs
        val ageMins  = TimeUnit.MILLISECONDS.toMinutes(ageMs)
        val isOnline = ageMins < 2

        holder.tvLastSeen.text = when {
            ageMins < 2  -> "🟢 Online now"
            ageMins < 60 -> "🟡 ${ageMins}m ago"
            else         -> "🔴 ${TimeUnit.MILLISECONDS.toHours(ageMs)}h ago"
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

        holder.btnLive.setOnClickListener   { onLiveScreen(item.deviceId) }
        holder.btnUsage.setOnClickListener  { onAppUsage(item.deviceId) }
        holder.btnDelete.setOnClickListener { onDelete(item.deviceId, item.deviceName) }
    }

    override fun getItemCount() = items.size
}
