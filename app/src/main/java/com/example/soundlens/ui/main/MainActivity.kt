package com.example.soundlens.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.ColorRes
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.soundlens.R
import com.example.soundlens.databinding.ActivityMainBinding
import com.example.soundlens.ui.result.ResultActivity
import com.example.soundlens.uiutils.AnimUtils
import com.example.soundlens.uiutils.Formatter
import com.example.soundlens.uiutils.PermissionUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.startRecording()
            else Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupExamplesDropdown()
        setupClicks()
        observeState()
        observeNavigation()
    }

    private fun setupExamplesDropdown() {
        val entries = vm.loadExamples(resources)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)
        binding.dropExamples.setAdapter(adapter)
        binding.dropExamples.threshold = 0
        binding.dropExamples.setOnClickListener { binding.dropExamples.showDropDown() }
        binding.dropExamples.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) binding.dropExamples.showDropDown() }
        binding.dropExamples.setOnTouchListener { _, _ -> binding.dropExamples.showDropDown(); false }
        binding.dropExamples.setOnItemClickListener { _, _, pos, _ ->
            val resId = when (pos) {
                0 -> R.raw.clip_one
                1 -> R.raw.clip_two
                2 -> R.raw.clip_three
                3 -> R.raw.clip_four
                else -> null
            }
            resId?.let { vm.onExamplePicked(entries[pos], it) }
        }
    }

    private fun setupClicks() {
        binding.mainCircle.setOnClickListener {
            when (vm.state.value?.mode) {
                MainUiState.Mode.IDLE -> checkPermissionAndRecord()
                MainUiState.Mode.RECORDING -> vm.stopRecording()
                MainUiState.Mode.READY,
                MainUiState.Mode.UPLOADING,
                MainUiState.Mode.IDENTIFYING,
                MainUiState.Mode.ERROR,
                null -> vm.togglePlay()
            }
        }
        binding.btnTrash.setOnClickListener { vm.clearAll() }
        binding.btnSend.setOnClickListener { vm.sendToIdentify() }
    }

    private fun checkPermissionAndRecord() {
        val ok = PermissionUtils.hasPermission(this, Manifest.permission.RECORD_AUDIO)
        if (ok) vm.startRecording() else audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun observeState() {
        vm.state.observe(this) { s ->

            fun setIcon(@DrawableRes resId: Int, @ColorRes tint: Int? = null) {
                binding.imgMainIcon.setImageResource(resId)
                if (tint == null) {
                    binding.imgMainIcon.imageTintList = null
                } else {
                    val c = androidx.core.content.ContextCompat.getColor(this, tint)
                    binding.imgMainIcon.imageTintList = android.content.res.ColorStateList.valueOf(c)
                }
                binding.imgMainIcon.invalidate()
            }

            when (s.mode) {
                MainUiState.Mode.IDLE -> {
                    setIcon(R.drawable.soundlens_logo, null)   // logo sin tinte
                    binding.playerBar.visibility = android.view.View.GONE
                    binding.btnTrash.visibility = android.view.View.GONE
                    com.example.soundlens.uiutils.AnimUtils.stopPulse(binding.mainCircle, binding.imgMainIcon)
                    binding.txtRecordTimer.visibility = android.view.View.GONE
                }
                MainUiState.Mode.RECORDING -> {
                    setIcon(R.drawable.ic_stop, R.color.sl_text_primary) // cuadrado bien visible
                    binding.playerBar.visibility = android.view.View.GONE
                    binding.btnTrash.visibility = android.view.View.GONE
                    com.example.soundlens.uiutils.AnimUtils.startPulse(binding.mainCircle, binding.imgMainIcon)
                    binding.txtRecordTimer.visibility = android.view.View.VISIBLE
                    binding.txtRecordTimer.text = com.example.soundlens.uiutils.Formatter.fmtMs(s.recordTimerMs)
                }
                else -> {
                    setIcon(
                        if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        R.color.sl_green
                    )
                    binding.playerBar.visibility = android.view.View.VISIBLE
                    binding.btnTrash.visibility = android.view.View.VISIBLE
                    com.example.soundlens.uiutils.AnimUtils.stopPulse(binding.mainCircle, binding.imgMainIcon)
                    binding.txtRecordTimer.visibility = android.view.View.GONE
                }
            }

            // Barra de reproducción
            binding.seek.max = s.elapsedMs + s.remainingMs
            binding.seek.progress = s.elapsedMs
            binding.txtElapsed.text = com.example.soundlens.uiutils.Formatter.fmtMs(s.elapsedMs)
            binding.txtRemaining.text = "-${com.example.soundlens.uiutils.Formatter.fmtMs(s.remainingMs)}"

            binding.btnSend.isEnabled = s.sendEnabled
            s.error?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private fun observeNavigation() {
        vm.goToResult.observe(this) { nav ->
            if (nav == null) return@observe
            val i = Intent(this, ResultActivity::class.java).apply {
                putExtra("payload_json", nav.payloadJson)
                putExtra("title", nav.title)
                putExtra("artist", nav.artist)
                putExtra("year", nav.year)
                putExtra("genre", nav.genre)
                putExtra("offset_frames", nav.offsetFrames)
                putExtra("matches_for_song", nav.matchesForSong)
                putExtra("matches_at_best_offset", nav.matchesAtBestOffset)
                putExtra("confidence", nav.confidence)
                putExtra("request_id", nav.requestId)
            }
            startActivity(i)
            vm.consumeGoToResult()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AnimUtils.stopPulse(binding.mainCircle, binding.imgMainIcon)
    }
}
