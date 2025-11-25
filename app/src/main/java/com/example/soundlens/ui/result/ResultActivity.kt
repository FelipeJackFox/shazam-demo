package com.example.soundlens.ui.result

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.soundlens.R
import com.example.soundlens.databinding.ActivityResultBinding
import com.example.soundlens.uiutils.Formatter
import com.github.mikephil.charting.charts.RadarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.RadarData
import com.github.mikephil.charting.data.RadarDataSet
import com.github.mikephil.charting.data.RadarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

private val RADAR_LABELS = arrayOf("RMS", "ZCR", "SC (Hz)")
private const val RADAR_FILL_ALPHA_CLIP = 120
private const val RADAR_FILL_ALPHA_IDEAL = 70
private const val RADAR_LINE_WIDTH = 2f
private const val RADAR_WEB_WIDTH = 1.2f
private const val RADAR_WEB_INNER_WIDTH = 1.0f
private const val RADAR_Y_MAX = 1f
private const val RADAR_Y_MIN = 0f
private const val RADAR_Y_GRANULARITY = 0.25f
private const val RADAR_SHOW_VALUES = false
private const val RMS_MAX = 0.60
private const val ZCR_MAX = 0.20
private const val SC_MAX_HZ = 6000.0

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private val vm: ResultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val payloadJson = intent.getStringExtra("payload_json") ?: "{}"
        val title = intent.getStringExtra("title") ?: "—"
        val artist = intent.getStringExtra("artist") ?: "—"
        val year = intent.getIntExtra("year", -1)
        val genre = intent.getStringExtra("genre") ?: ""
        val offsetFrames = intent.getIntExtra("offset_frames", -1)

        vm.initWithPayload(payloadJson, title, artist, year, genre, offsetFrames)

        setupClicks()
        observeState()
    }

    private fun setupClicks() {
        binding.btnPlay.setOnClickListener { vm.togglePlay() }
        binding.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.txtElapsed.text = Formatter.fmtMs(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.btnToggleDebug.setOnClickListener {
            val showing = binding.txtDebug.isVisible
            binding.txtDebug.visibility = if (showing) View.GONE else View.VISIBLE
            binding.btnToggleDebug.text = if (showing) "Debug log ▾" else "Debug log ▴"
        }
    }

    private fun observeState() {
        vm.state.observe(this) { s ->
            binding.txtSong.text = s.metaTitle
            binding.txtMeta.text = s.metaSubtitle
            binding.progress.visibility = if (s.loading) View.VISIBLE else View.GONE

            binding.btnPlay.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            binding.seek.max = s.elapsedMs + s.remainingMs
            binding.seek.progress = s.elapsedMs
            binding.txtElapsed.text = Formatter.fmtMs(s.elapsedMs)
            binding.txtRemaining.text = "-${Formatter.fmtMs(s.remainingMs)}"

            binding.txtDebug.text = s.prettyJson

            if (s.showRadar) renderRadarChart()

            s.error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun renderRadarChart() {
        val r = vm.state.value?.response ?: return
        val clip = r.clipFeatures
        val ideal = r.idealFeatures
        if (clip == null || ideal == null) {
            binding.radarChart.visibility = View.GONE
            return
        }

        fun cap(value: Double?, max: Double): Float {
            val v = (value ?: 0.0).coerceAtLeast(0.0).coerceAtMost(max)
            return (v / max).toFloat()
        }

        val clipEntries = listOf(
            RadarEntry(cap(clip.rms, RMS_MAX)),
            RadarEntry(cap(clip.zcr, ZCR_MAX)),
            RadarEntry(cap(clip.sc_hz, SC_MAX_HZ))
        )
        val idealEntries = listOf(
            RadarEntry(cap(ideal.rms, RMS_MAX)),
            RadarEntry(cap(ideal.zcr, ZCR_MAX)),
            RadarEntry(cap(ideal.sc_hz, SC_MAX_HZ))
        )

        val chart: RadarChart = binding.radarChart
        chart.visibility = View.VISIBLE

        val clipColor = ContextCompat.getColor(this, R.color.sl_green)
        val idealColor = ContextCompat.getColor(this, R.color.sl_text_secondary)
        val labelColor = ContextCompat.getColor(this, R.color.sl_text_secondary)

        val setClip = RadarDataSet(clipEntries, "Clip").apply {
            color = clipColor
            fillColor = clipColor
            setDrawFilled(true)
            fillAlpha = RADAR_FILL_ALPHA_CLIP
            lineWidth = RADAR_LINE_WIDTH
            setDrawValues(RADAR_SHOW_VALUES)
        }
        val setIdeal = RadarDataSet(idealEntries, "Ideal").apply {
            color = idealColor
            fillColor = idealColor
            setDrawFilled(true)
            fillAlpha = RADAR_FILL_ALPHA_IDEAL
            lineWidth = RADAR_LINE_WIDTH
            setDrawValues(RADAR_SHOW_VALUES)
        }

        chart.data = RadarData(setClip, setIdeal)
        chart.description.isEnabled = false
        chart.isRotationEnabled = false
        chart.webLineWidth = RADAR_WEB_WIDTH
        chart.webLineWidthInner = RADAR_WEB_INNER_WIDTH
        chart.webColor = labelColor
        chart.webColorInner = labelColor

        chart.xAxis.apply {
            valueFormatter = IndexAxisValueFormatter(RADAR_LABELS)
            textColor = labelColor
            textSize = 12f
            position = XAxis.XAxisPosition.TOP
        }
        chart.yAxis.apply {
            axisMinimum = RADAR_Y_MIN
            axisMaximum = RADAR_Y_MAX
            granularity = RADAR_Y_GRANULARITY
            setDrawLabels(false)
            textColor = labelColor
        }
        chart.legend.apply {
            textColor = labelColor
            textSize = 12f
            isWordWrapEnabled = true
        }

        chart.animateXY(500, 500)
        chart.invalidate()
    }
}
