package com.parentalcontrol.parentview

import android.content.Intent
import android.os.Bundle
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
 * Lists ALL connected kid devices in real-time.
 * Each card: device name, status, [📺 Live Screen] [📊 App Usage] [🗑 Remove]
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rvDevices: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    private val devicesRef = FirebaseDatabase.getInstance().getReference("devices")
    private var devicesListener: ValueEventListener? = null

    private val deviceAdapter = DeviceAdapter(
        onLiveScreen = { deviceId ->
            startActivity(Intent(this, LiveScreenActivity::class.java).apply {
                putExtra("deviceId", deviceId)
            })
        },
        onAppUsage = { deviceId ->
            startActivity(Intent(this, AppUsageActivity::class.java).apply {
                putExtra("deviceId", deviceId)
            })
        },
        onDelete = { deviceId, deviceName ->
            confirmDelete(deviceId, deviceName)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "👨‍👧 Parent Monitor"

        rvDevices   = findViewById(R.id.rvDevices)
        tvEmpty     = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        startListeningDevices()
    }

    private fun startListeningDevices() {
        progressBar.visibility = View.VISIBLE
        devicesListener = devicesRef.addValueEventListener(object : ValueEventListener {
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
                devicesRef.child(deviceId).removeValue()
                    .addOnSuccessListener {
                        Toast.makeText(this, "✅ Device removed", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "❌ Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        devicesListener?.let { devicesRef.removeEventListener(it) }
        super.onDestroy()
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
