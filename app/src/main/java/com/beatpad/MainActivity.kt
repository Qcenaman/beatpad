package com.beatpad

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.beatpad.audio.RecSource
import com.beatpad.ui.BeatPadApp

interface Actions {
    fun record(source: RecSource, target: RecTarget)
    fun importFile(pad: Int)
    fun export(bars: Int)
}

class MainActivity : ComponentActivity(), Actions {
    private val vm: MainViewModel by viewModels()
    private var pending: Pair<RecSource, RecTarget>? = null
    private var importPad = 0
    private var exportBars = 4

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMic()) proceed() else vm.toast("BeatPad needs microphone permission to record")
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val target = pending?.second
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null && target != null) {
            vm.startRecording(RecSource.PHONE, target, r.resultCode, data)
        } else {
            vm.toast("Phone audio capture was cancelled")
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSample(uri, importPad)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) vm.exportBeat(uri, exportBars)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BeatPadApp(vm, this) }
    }

    override fun onStart() {
        super.onStart()
        vm.engine.active = true
    }

    override fun onStop() {
        super.onStop()
        vm.engine.active = false
        vm.saveState()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun record(source: RecSource, target: RecTarget) {
        pending = source to target
        val perms = mutableListOf<String>()
        if (!hasMic()) perms += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perms += Manifest.permission.POST_NOTIFICATIONS
        if (perms.isEmpty() || hasMic() && perms.size == 1 && Build.VERSION.SDK_INT >= 33 && !shouldAskNotif) {
            proceed()
        } else {
            shouldAskNotif = false
            permLauncher.launch(perms.toTypedArray())
        }
    }

    private var shouldAskNotif = true

    private fun proceed() {
        val (source, target) = pending ?: return
        if (source == RecSource.PHONE) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            projectionLauncher.launch(intent)
        } else {
            vm.startRecording(source, target)
        }
    }

    override fun importFile(pad: Int) {
        importPad = pad
        importLauncher.launch(arrayOf("audio/*", "video/*"))
    }

    override fun export(bars: Int) {
        exportBars = bars
        exportLauncher.launch("beatpad-beat.wav")
    }
}
