package com.example.screenrec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

enum class AudioMode(val title: String, val hasSystem: Boolean, val hasMic: Boolean) {
    BOTH("Системный звук + микрофон", true, true),
    SYSTEM("Только системный звук", true, false),
    MIC("Только микрофон", false, true),
    NONE("Без звука", false, false);

    val hasAudio: Boolean get() = hasSystem || hasMic
}

class MainActivity : Activity() {

    private lateinit var group: RadioGroup
    private lateinit var button: Button
    private lateinit var hint: TextView

    private val selectedMode: AudioMode
        get() = AudioMode.entries[group.checkedRadioButtonId - 1]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Запись экрана"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "Что записывать вместе с видео:"
            textSize = 16f
            setPadding(0, pad, 0, pad / 3)
        })

        val saved = getPreferences(MODE_PRIVATE).getInt("mode", 0)
        group = RadioGroup(this)
        AudioMode.entries.forEachIndexed { i, mode ->
            group.addView(RadioButton(this).apply {
                id = i + 1
                text = mode.title
                textSize = 17f
            })
        }
        group.check(saved + 1)
        group.setOnCheckedChangeListener { _, id ->
            getPreferences(MODE_PRIVATE).edit().putInt("mode", id - 1).apply()
        }
        root.addView(group)

        button = Button(this).apply {
            textSize = 18f
            setOnClickListener { onButton() }
        }
        root.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        hint = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.START
            setPadding(0, pad, 0, 0)
            text = "Видео сохраняются в папку Movies/ScreenRecorder.\n\n" +
                "Совет: при записи «системный звук + микрофон» лучше надеть наушники, " +
                "иначе микрофон повторно запишет звук из динамика (будет эхо)."
        }
        root.addView(hint)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        RecordService.listener = { runOnUiThread { refreshUi() } }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        RecordService.listener = null
    }

    private fun refreshUi() {
        val rec = RecordService.isRecording
        button.text = if (rec) "■  Остановить запись" else "●  Начать запись"
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !rec
    }

    private fun onButton() {
        if (RecordService.isRecording) {
            startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            return
        }
        val needed = mutableListOf<String>()
        if (selectedMode.hasAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) askScreenCapture()
        else requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        if (selectedMode.hasAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Для записи звука нужно разрешение «Микрофон». Без него можно выбрать «Без звука».",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        askScreenCapture()
    }

    private fun askScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Запись отменена", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, RecordService::class.java)
            .setAction(RecordService.ACTION_START)
            .putExtra(RecordService.EXTRA_CODE, resultCode)
            .putExtra(RecordService.EXTRA_DATA, data)
            .putExtra(RecordService.EXTRA_MODE, selectedMode.name)
        startForegroundService(intent)
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_CAPTURE = 2
    }
}

class RecordService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL = "recording"
        private const val TAG = "ScreenRec"

        @Volatile var isRecording = false
            private set
        @Volatile var listener: (() -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var videoUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
            ACTION_START -> if (!isRecording) {
                val mode = AudioMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: AudioMode.NONE.name)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)

                // Сначала foreground — без этого Android 14+ не даст доступ к экрану.
                goForeground(mode)
                try {
                    startRecording(code, data!!, mode)
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось начать запись", e)
                    toast("Не удалось начать запись: ${e.message}")
                    stopRecording()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(mode: AudioMode) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Запись экрана", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Идёт запись экрана")
            .setContentText("Звук: ${mode.title}")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Остановить", stopPi).build())
            .build()

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (mode.hasMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, notification, type)
    }

    private fun startRecording(code: Int, data: Intent, mode: AudioMode) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: error("нет доступа к экрану")
        projection = proj
        // Обязательно до createVirtualDisplay (Android 14+).
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopRecording(); stopSelf() }
            }
        }, main)

        // Размер видео: как экран, но не больше 1920 по длинной стороне, кратно 16.
        val (sw, sh) = screenSize()
        val scale = min(1f, 1920f / max(sw, sh))
        val w = ((sw * scale).toInt() / 16) * 16
        val h = ((sh * scale).toInt() / 16) * 16
        val dpi = resources.displayMetrics.densityDpi

        val name = "Screen_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("не удалось создать файл")
        videoUri = uri
        val fd = contentResolver.openFileDescriptor(uri, "rw") ?: error("не удалось открыть файл")
        pfd = fd

        recorder = ScreenRecorder(proj, mode, w, h, dpi, fd.fileDescriptor).also { it.start() }
        isRecording = true
        listener?.invoke()
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun stopRecording() {
        val rec = recorder
        val proj = projection
        val uri = videoUri
        val fd = pfd
        recorder = null; projection = null; videoUri = null; pfd = null
        val wasRecording = isRecording
        isRecording = false
        if (rec == null && proj == null && uri == null) return

        val ok = try { rec?.stop() ?: false } catch (e: Exception) {
            Log.e(TAG, "Ошибка при остановке", e); false
        }
        try { proj?.stop() } catch (_: Exception) {}
        try { fd?.close() } catch (_: Exception) {}

        if (uri != null) {
            if (ok) {
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
                toast("Видео сохранено в Movies/ScreenRecorder")
            } else {
                contentResolver.delete(uri, null, null)
                if (wasRecording) toast("Запись слишком короткая или не удалась")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        listener?.invoke()
    }

    private fun toast(text: String) = main.post {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}

/**
 * Пишет экран (H.264) + звук (AAC) в один MP4.
 * Звук: системный (AudioPlaybackCapture), микрофон, или оба, смешанные вместе.
 */
class ScreenRecorder(
    private val projection: MediaProjection,
    private val mode: AudioMode,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    fd: FileDescriptor,
) {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHUNK = 1024 // сэмплов за одно чтение
    }

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val expectedTracks = if (mode.hasAudio) 2 else 1
    private var addedTracks = 0
    private var muxerStarted = false
    private val pending = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()

    @Volatile private var running = false
    private lateinit var videoEncoder: MediaCodec
    private var audioEncoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var micRecord: AudioRecord? = null
    private var sysRecord: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun start() {
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoEncoder.createInputSurface()
        videoEncoder.start()

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecorder", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )

        if (mode.hasAudio) setupAudio()

        running = true
        videoThread = thread(name = "video-encoder") { videoLoop() }
        if (mode.hasAudio) audioThread = thread(name = "audio-encoder") { audioLoop() }
    }

    @SuppressLint("MissingPermission") // разрешение проверяется в MainActivity
    private fun setupAudio() {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf * 2, CHUNK * 2 * 8)

        if (mode.hasMic) {
            micRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()
        }
        if (mode.hasSystem) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            sysRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()
        }

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2 * 2)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    // ---------- Видео ----------

    private var videoTrack = -1

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) drain(videoEncoder, info, isVideo = true, untilEos = false)
        try {
            videoEncoder.signalEndOfInputStream()
            drain(videoEncoder, info, isVideo = true, untilEos = true)
        } catch (_: Exception) {}
    }

    // ---------- Звук ----------

    private var audioTrack = -1

    private fun audioLoop() {
        val enc = audioEncoder!!
        val mic = micRecord
        val sys = sysRecord
        val micBuf = ShortArray(CHUNK)
        val sysBuf = ShortArray(CHUNK)
        val mix = ShortArray(CHUNK)
        val info = MediaCodec.BufferInfo()

        mic?.startRecording()
        sys?.startRecording()
        val startUs = System.nanoTime() / 1000
        var totalSamples = 0L

        while (running) {
            var count = CHUNK
            if (mic != null) {
                val r = mic.read(micBuf, 0, CHUNK)
                if (r <= 0) continue
                count = r
            }
            if (sys != null) {
                val r = sys.read(sysBuf, 0, count)
                if (r <= 0) continue
                count = min(count, r)
            }
            // Смешиваем: складываем сигналы и обрезаем, чтобы не было переполнения.
            for (i in 0 until count) {
                var s = 0
                if (mic != null) s += micBuf[i]
                if (sys != null) s += sysBuf[i]
                mix[i] = s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            val idx = enc.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = enc.getInputBuffer(idx)!!
                buf.clear()
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(mix, 0, count)
                val ptsUs = startUs + totalSamples * 1_000_000L / SAMPLE_RATE
                enc.queueInputBuffer(idx, 0, count * 2, ptsUs, 0)
            }
            totalSamples += count
            drain(enc, info, isVideo = false, untilEos = false)
        }

        try {
            var idx = -1
            for (i in 0 until 50) {
                idx = enc.dequeueInputBuffer(10_000)
                if (idx >= 0) break
                drain(enc, info, isVideo = false, untilEos = false)
            }
            if (idx >= 0) {
                val ptsUs = startUs + totalSamples * 1_000_000L / SAMPLE_RATE
                enc.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(enc, info, isVideo = false, untilEos = true)
            }
        } catch (_: Exception) {}

        try { mic?.stop() } catch (_: Exception) {}
        try { sys?.stop() } catch (_: Exception) {}
        mic?.release()
        sys?.release()
    }

    // ---------- Общее ----------

    /** Забирает готовые данные из кодека и пишет в файл. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, isVideo: Boolean, untilEos: Boolean) {
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos || ++waits > 200) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val t = addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = t else audioTrack = t
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0) {
                        writeSample(if (isVideo) videoTrack else audioTrack, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    @Synchronized
    private fun addTrack(format: MediaFormat): Int {
        val t = muxer.addTrack(format)
        addedTracks++
        if (addedTracks == expectedTracks) {
            muxer.start()
            muxerStarted = true
            for ((track, data, info) in pending) muxer.writeSampleData(track, data, info)
            pending.clear()
        }
        return t
    }

    @Synchronized
    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        if (muxerStarted) {
            muxer.writeSampleData(track, buf, info)
        } else {
            // Пока не готовы обе дорожки — держим данные в памяти.
            val copy = ByteBuffer.allocateDirect(info.size)
            copy.put(buf)
            copy.flip()
            val ci = MediaCodec.BufferInfo()
            ci.set(0, info.size, info.presentationTimeUs, info.flags)
            pending.add(Triple(track, copy, ci))
        }
    }

    /** Останавливает запись. Возвращает true, если файл получился. */
    fun stop(): Boolean {
        running = false
        videoThread?.join(5000)
        audioThread?.join(5000)
        virtualDisplay?.release()
        try { videoEncoder.stop() } catch (_: Exception) {}
        videoEncoder.release()
        audioEncoder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        val ok = synchronized(this) { muxerStarted }
        if (ok) {
            try { muxer.stop() } catch (_: Exception) { return false.also { muxer.release() } }
        }
        muxer.release()
        return ok
    }
}
