package com.beatpad.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.beatpad.MainActivity
import com.beatpad.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max

enum class RecSource { MIC, SPEAKER, PHONE }

/** Shared recording state between the service and the UI. */
object RecordingHub {
    sealed class State {
        object Idle : State()
        data class Recording(val source: RecSource, val startedAt: Long) : State()
        class Done(val source: RecSource, val data: FloatArray, val error: String?) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    @Volatile var level = 0f
}

/**
 * Foreground service that records from the mic, or captures what other apps are playing.
 * Running as a service lets recording continue while you're in another app.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var finished = true
    private var buf = FloatGrow()
    private var source = RecSource.MIC
    private var channels = 1
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (finished) stopSelf() else finish(null)
            ACTION_START -> if (finished) start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        finished = false
        buf = FloatGrow(SR * 10)
        source = RecSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: "MIC")
        createChannel()
        val type = if (source == RecSource.PHONE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            finish("Couldn't start recording (${e.message})")
            return
        }
        try {
            val rec = if (source == RecSource.PHONE) createCapture(intent) else createMic()
            record = rec
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("microphone is busy")
            running = true
            RecordingHub.level = 0f
            RecordingHub.state.value = RecordingHub.State.Recording(source, System.currentTimeMillis())
            thread = Thread({ readLoop(rec) }, "BeatPadRec").apply { start() }
        } catch (e: Exception) {
            finish(e.message ?: "unknown error")
        }
    }

    private fun readLoop(rec: AudioRecord) {
        val chunk = FloatArray(1024 * channels)
        val maxFrames = SR * MAX_SECONDS
        while (running) {
            val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                if (n < 0) try { Thread.sleep(5) } catch (_: InterruptedException) {}
                continue
            }
            var pk = 0f
            val frames = n / channels
            for (f in 0 until frames) {
                val v = if (channels == 2) (chunk[2 * f] + chunk[2 * f + 1]) * 0.5f else chunk[f]
                buf.add(v)
                pk = max(pk, abs(v))
            }
            RecordingHub.level = pk
            if (buf.size >= maxFrames) {
                running = false
                main.post { finish(null) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMic(): AudioRecord {
        channels = 1
        val am = getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val src = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SR)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioRecord.Builder()
            .setAudioSource(src)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(max(minBuf, SR / 5 * 4) * 2)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun createCapture(intent: Intent): AudioRecord {
        channels = 2
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA))
            ?: throw IllegalStateException("missing permission result")
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("permission was not granted")
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!finished) finish(null)
            }
        }, main)
        projection = p
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SR)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(max(minBuf, SR / 5 * 8) * 2)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
    }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        RecordingHub.level = 0f
        RecordingHub.state.value = RecordingHub.State.Done(source, buf.toArray(), error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) finish(null)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (source) {
            RecSource.MIC -> "Recording from the microphone"
            RecSource.SPEAKER -> "Recording through the speaker. Play your sound, then tap Stop."
            RecSource.PHONE -> "Capturing phone audio. Play your sound, then tap Stop."
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("BeatPad is recording")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.beatpad.START"
        const val ACTION_STOP = "com.beatpad.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CHANNEL = "rec"
        const val NOTIF_ID = 7
        const val MAX_SECONDS = 120

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
