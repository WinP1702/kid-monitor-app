package com.parentalcontrol.parentview

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Shows per-app screen time for the child device.
 * Displays a horizontal bar chart + detailed list.
 */
class AppUsageActivity : AppCompatActivity() {

    private lateinit var barChart: HorizontalBarChart
    private lateinit var rvApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDate: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvNoData: TextView

    private val appAdapter = AppUsageAdapter()
    private var deviceId: String = ""
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_usage)
        supportActionBar?.title = "📊 App Usage"

        deviceId = intent.getStringExtra("deviceId") ?: run { finish(); return }

        barChart = findViewById(R.id.barChart)
        rvApps = findViewById(R.id.rvApps)
        progressBar = findViewById(R.id.progressBar)
        tvDate = findViewById(R.id.tvDate)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvNoData = findViewById(R.id.tvNoData)

        tvDate.text = "Today – $today"

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = appAdapter

        setupChart()
        loadUsageData()
    }

    private fun setupChart() {
        barChart.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            setFitBars(true)
            legend.isEnabled = false
            setBackgroundColor(Color.parseColor("#121228"))

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = Color.WHITE
                textSize = 10f
            }

            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#2A2A4A")
                textColor = Color.WHITE
                axisMinimum = 0f
            }

            axisRight.isEnabled = false
            animateY(800)
        }
    }

    private fun loadUsageData() {
        progressBar.visibility = View.VISIBLE
        val ref = FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/appUsage/$today")

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                progressBar.visibility = View.GONE

                val apps = mutableListOf<AppUsageItem>()
                for (child in snapshot.children) {
                    val appName = child.child("appName").getValue(String::class.java) ?: continue
                    val totalMins = child.child("totalTimeMinutes").getValue(Long::class.java) ?: 0L
                    val lastUsed = child.child("lastUsed").getValue(Long::class.java) ?: 0L
                    apps.add(AppUsageItem(appName, totalMins, lastUsed))
                }

                apps.sortByDescending { it.totalMinutes }

                if (apps.isEmpty()) {
                    tvNoData.visibility = View.VISIBLE
                    barChart.visibility = View.GONE
                    return
                }

                tvNoData.visibility = View.GONE
                barChart.visibility = View.VISIBLE

                val totalMins = apps.sumOf { it.totalMinutes }
                tvTotalTime.text = "Total screen time: ${formatMinutes(totalMins)}"

                appAdapter.submitList(apps)
                updateChart(apps.take(8))  // Show top 8 in chart
            }

            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@AppUsageActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun updateChart(apps: List<AppUsageItem>) {
        val entries = apps.mapIndexed { i, app ->
            BarEntry(i.toFloat(), app.totalMinutes.toFloat())
        }

        val dataSet = BarDataSet(entries, "Minutes").apply {
            colors = listOf(
                Color.parseColor("#4FC3F7"), Color.parseColor("#81D4FA"),
                Color.parseColor("#29B6F6"), Color.parseColor("#0288D1"),
                Color.parseColor("#0277BD"), Color.parseColor("#B3E5FC"),
                Color.parseColor("#E1F5FE"), Color.parseColor("#4DD0E1")
            )
            valueTextColor = Color.WHITE
            valueTextSize = 10f
        }

        barChart.xAxis.valueFormatter = IndexAxisValueFormatter(apps.map { it.appName.take(12) })
        barChart.xAxis.labelCount = apps.size
        barChart.data = BarData(dataSet)
        barChart.invalidate()
    }

    private fun formatMinutes(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

// ─── Data & Adapter ───────────────────────────────────────────────────────────

data class AppUsageItem(val appName: String, val totalMinutes: Long, val lastUsed: Long)

class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.VH>() {
    private var items = listOf<AppUsageItem>()

    fun submitList(list: List<AppUsageItem>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvApp: TextView = view.findViewById(R.id.tvAppName)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val progressBar: ProgressBar = view.findViewById(R.id.progressUsage)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_usage, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val maxMins = items.firstOrNull()?.totalMinutes ?: 1L
        holder.tvApp.text = item.appName
        holder.tvTime.text = "${item.totalMinutes}m"
        holder.progressBar.max = maxMins.toInt()
        holder.progressBar.progress = item.totalMinutes.toInt()
    }

    override fun getItemCount() = items.size
}
