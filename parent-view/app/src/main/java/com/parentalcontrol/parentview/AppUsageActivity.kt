package com.parentalcontrol.parentview

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Shows per-app screen time for the child device.
 * Reads from Supabase Postgres (app_usage table) via REST.
 * Zero Firebase dependencies.
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
    private var pairingKey: String = ""
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_usage)
        supportActionBar?.title = "📊 App Usage"

        deviceId   = intent.getStringExtra("deviceId") ?: run { finish(); return }
        pairingKey = intent.getStringExtra("pairingKey") ?: ""

        barChart    = findViewById(R.id.barChart)
        rvApps      = findViewById(R.id.rvApps)
        progressBar = findViewById(R.id.progressBar)
        tvDate      = findViewById(R.id.tvDate)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvNoData    = findViewById(R.id.tvNoData)

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

        lifecycleScope.launch {
            val apps = fetchUsageFromSupabase()

            progressBar.visibility = View.GONE

            if (apps.isEmpty()) {
                tvNoData.visibility = View.VISIBLE
                barChart.visibility = View.GONE
                return@launch
            }

            tvNoData.visibility = View.GONE
            barChart.visibility = View.VISIBLE

            val totalMins = apps.sumOf { it.totalMinutes }
            tvTotalTime.text = "Total screen time: ${formatMinutes(totalMins)}"

            appAdapter.submitList(apps)
            updateChart(apps.take(8))
        }
    }

    /** Query Supabase app_usage table filtered by device_id + date */
    private suspend fun fetchUsageFromSupabase(): List<AppUsageItem> = withContext(Dispatchers.IO) {
        try {
            val supabaseUrl = BuildConfig.SUPABASE_URL
            val supabaseKey = BuildConfig.SUPABASE_KEY
            val url = "$supabaseUrl/rest/v1/app_usage" +
                "?select=app_name,total_minutes,last_used" +
                "&device_id=eq.$deviceId" +
                "&date=eq.$today" +
                "&order=total_minutes.desc"

            val request = Request.Builder()
                .url(url)
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Accept", "application/json")
                .get()
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(body)
                val apps = mutableListOf<AppUsageItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val appName    = obj.optString("app_name", "Unknown")
                    val totalMins  = obj.optLong("total_minutes", 0L)
                    val lastUsed   = obj.optLong("last_used", 0L)
                    if (totalMins > 0) apps.add(AppUsageItem(appName, totalMins, lastUsed))
                }
                apps
            }
        } catch (e: Exception) {
            emptyList()
        }
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
