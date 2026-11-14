package com.example.helmetrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Полудуплексная ретрансляция звука.
 *  LISTEN: микрофон телефона -> динамики шлема (A2DP, при его отсутствии SCO).
 *  TALK:   микрофон шлема (SCO) -> динамик телефона.
 * В каждый момент работает только один канал.
 */
class RelayService : Service() {

    enum class Mode { IDLE, LISTEN, TALK }

    companion object {
        const val ACTION_LISTEN = "com.example.helmetrelay.LISTEN"
        const val ACTION_TALK = "com.example.helmetrelay.TALK"
        const val ACTION_STOP = "com.example.helmetrelay.STOP"

        private const val CHANNEL_ID = "relay"
        private const val NOTIF_ID = 1
        private const val RATE = 16000
        private const val CHUNK = 320 // 20 мс при 16 кГц

        @Volatile
        var mode: Mode = Mode.IDLE

        /** Программное усиление сигнала в режиме TALK (микрофон шлема -> динамик телефона). */
        @Volatile
        var talkGain: Float = 4f

        /** true: автоматическая регулировка усиления (talkGain тогда служит верхним пределом). */
        @Volatile
        var talkAuto: Boolean = true

        /** Источник захвата для режима TALK (задаётся из UI). */
        @Volatile
        var talkSource: Int = MediaRecorder.AudioSource.MIC

        /** Целевой пиковый уровень автоусиления в режиме TALK (из 32767). */
        @Volatile
        var talkTarget: Float = 20000f

        /** Режим LISTEN: порог тишины по пику (0 = выключен). Ниже порога звук в шлем не передаётся. */
        @Volatile
        var listenGate: Int = 0

        /** Режим LISTEN: полосовой фильтр 300–3400 Гц и шумоподавитель системы. */
        @Volatile
        var listenFilter: Boolean = true

        @Volatile
        var listener: ((Mode, List<String>) -> Unit)? = null

        private val logLines = ArrayDeque<String>()

        fun currentLog(): List<String> = synchronized(logLines) { logLines.toList() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor()

    private var worker: Thread? = null

    @Volatile
    private var running = false
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var savedMusic = -1
    private var savedCall = -1
    private var ns: NoiseSuppressor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                control.execute {
                    applyMode(Mode.IDLE)
                    main.post {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> {
                startFg(Mode.LISTEN)
                control.execute { applyMode(Mode.LISTEN) }
            }
            ACTION_TALK -> {
                startFg(Mode.TALK)
                control.execute { applyMode(Mode.TALK) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWorker()
        restoreAudio()
        control.shutdownNow()
        mode = Mode.IDLE
        publish()
        super.onDestroy()
    }

    // ---------- уведомление ----------

    private fun startFg(m: Mode) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HelmetRelay", NotificationManager.IMPORTANCE_LOW)
        )
        val title = if (m == Mode.TALK) "Режим 2: говорить (шлем → телефон)" else "Режим 1: слушать (телефон → шлем)"
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setOngoing(true)
            .addAction(action("1 Слушать", ACTION_LISTEN, 1))
            .addAction(action("2 Говорить", ACTION_TALK, 2))
            .addAction(action("Стоп", ACTION_STOP, 3))
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun action(title: String, act: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, RelayService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_play), title, pi
        ).build()
    }

    // ---------- логика режимов (выполняется в потоке control) ----------

    private fun applyMode(m: Mode) {
        stopWorker()
        restoreAudio()
        mode = Mode.IDLE
        try {
            when (m) {
                Mode.LISTEN -> startListen()
                Mode.TALK -> startTalk()
                Mode.IDLE -> log("Остановлено")
            }
        } catch (e: Exception) {
            log("Ошибка: ${e.javaClass.simpleName}: ${e.message}")
            stopWorker()
            restoreAudio()
            mode = Mode.IDLE
        }
        publish()
    }

    private fun startListen() {
        val am = getSystemService(AudioManager::class.java)
        val micIn = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var out = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        var usage = AudioAttributes.USAGE_MEDIA
        if (out == null) {
            val sco = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (sco != null) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                log("A2DP не найден, выход через SCO: setCommunicationDevice=${am.setCommunicationDevice(sco)}")
                Thread.sleep(1000)
                out = sco
                usage = AudioAttributes.USAGE_VOICE_COMMUNICATION
            }
        }
        if (out == null) {
            log("Шлем не найден среди аудиоустройств. Проверьте подключение.")
            return
        }
        log("LISTEN: вход=${name(micIn)}, выход=${name(out)}")
        val r = buildRecord(MediaRecorder.AudioSource.MIC, micIn) ?: return
        if (listenFilter) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(r.audioSessionId)
                    ns?.setEnabled(true)
                    log("NoiseSuppressor: включён=${ns?.getEnabled()}")
                } else {
                    log("NoiseSuppressor на этом телефоне недоступен")
                }
            } catch (e: Exception) {
                log("NoiseSuppressor не удалось включить: ${e.javaClass.simpleName}")
            }
        }
        val t = buildTrack(usage, out)
        if (t == null) {
            r.release()
            return
        }
        startWorker(r, t, Mode.LISTEN)
    }

    private fun startTalk() {
        val am = getSystemService(AudioManager::class.java)
        val sco = am.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (sco == null) {
            log("SCO-устройство (гарнитурный канал шлема) не найдено.")
            return
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        log("TALK: setCommunicationDevice(SCO)=${am.setCommunicationDevice(sco)}")
        Thread.sleep(1000) // время на поднятие SCO-канала
        try {
            if (savedMusic < 0) savedMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (savedCall < 0) savedCall = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            log(
                "Громкость: музыка=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, " +
                    "разговор=${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}"
            )
        } catch (e: Exception) {
            log("Не удалось выставить громкость: ${e.javaClass.simpleName}")
        }
        val btIn = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val spk = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        log("TALK: вход=${name(btIn)}, выход=${name(spk)}")
        if (btIn == null || spk == null) {
            log("Не найдено нужное устройство ввода или вывода.")
            return
        }
        log("TALK: источник захвата=${talkSource} (1=MIC, 6=VOICE_RECOGNITION, 7=VOICE_COMMUNICATION)")
        val r = buildRecord(talkSource, btIn) ?: return
        val t = buildTrack(AudioAttributes.USAGE_MEDIA, spk)
        if (t == null) {
            r.release()
            return
        }
        startWorker(r, t, Mode.TALK)
    }

    private fun restoreAudio() {
        try {
            val am = getSystemService(AudioManager::class.java)
            if (savedMusic >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0)
            if (savedCall >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCall, 0)
            savedMusic = -1
            savedCall = -1
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    // ---------- аудио ----------

    private fun buildRecord(source: Int, dev: AudioDeviceInfo?): AudioRecord? {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minB = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minB, CHUNK * 8))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            log("AudioRecord не инициализирован (разрешение микрофона?)")
            r.release()
            return null
        }
        if (dev != null) log("AudioRecord.setPreferredDevice=${r.setPreferredDevice(dev)}")
        return r
    }

    private fun buildTrack(usage: Int, dev: AudioDeviceInfo?): AudioTrack? {
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minB = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minB, CHUNK * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            log("AudioTrack не инициализирован")
            t.release()
            return null
        }
        if (dev != null) log("AudioTrack.setPreferredDevice=${t.setPreferredDevice(dev)}")
        return t
    }

    private fun startWorker(r: AudioRecord, t: AudioTrack, m: Mode) {
        rec = r
        track = t
        running = true
        mode = m
        worker = Thread {
            val buf = ShortArray(CHUNK)
            try {
                r.startRecording()
                t.play()
                var first = true
                var peak = 0
                var chunks = 0
                var reports = 0
                var agc = 8f
                val hp = biquad(true, 300f)
                val lp = biquad(false, 3400f)
                var gate = 1f
                var holdChunks = 0
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) {
                        // пиковый уровень исходного сигнала (до усиления), отчёт раз в ~2 с, первые 4 раза
                        var cp = 0
                        for (i in 0 until n) {
                            val a = kotlin.math.abs(buf[i].toInt())
                            if (a > cp) cp = a
                        }
                        if (cp > peak) peak = cp
                        if (++chunks >= 100) {
                            if (reports < 6) {
                                reports++
                                val gainInfo = if (m == Mode.TALK) {
                                    ", усиление ×${"%.1f".format(if (talkAuto) agc.coerceAtMost(talkGain) else talkGain)}" +
                                        (if (talkAuto) " (авто)" else "")
                                } else ""
                                log("Пик сигнала с микрофона: $peak из 32767$gainInfo")
                            }
                            peak = 0
                            chunks = 0
                        }
                        val maxG = talkGain
                        var g = maxG
                        if (m == Mode.TALK && talkAuto) {
                            // АРУ: цель задаётся talkTarget; ниже порога 50 (пауза) усиление не меняется
                            if (cp >= 50) {
                                val desired = (talkTarget / cp).coerceIn(1f, maxG)
                                agc = if (desired < agc) desired else minOf(desired, agc * 1.06f)
                                if (cp * agc > 29000f) agc = 29000f / cp // ограничитель от перегруза
                            }
                            g = agc.coerceAtMost(maxG)
                        }
                        if (m == Mode.TALK && g != 1f) {
                            for (i in 0 until n) {
                                buf[i] = softClip(buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                        if (m == Mode.LISTEN) {
                            // порог тишины по пику исходного сигнала; после речи канал держится открытым ~300 мс
                            val th = listenGate
                            if (th > 0) {
                                if (cp >= th) holdChunks = 15 else if (holdChunks > 0) holdChunks--
                            }
                            val tgt = if (th <= 0 || holdChunks > 0) 1f else 0f
                            val filt = listenFilter
                            for (i in 0 until n) {
                                var x = buf[i].toFloat()
                                if (filt) x = lp.process(hp.process(x))
                                gate = if (gate < tgt) minOf(tgt, gate + 1f / 80f) else maxOf(tgt, gate - 1f / 320f)
                                buf[i] = (x * gate).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        }
                        t.write(buf, 0, n)
                        if (first) {
                            first = false
                            log("Фактический маршрут: вход=${name(r.routedDevice)}, выход=${name(t.routedDevice)}")
                            publish()
                        }
                    } else if (n < 0) {
                        log("Ошибка чтения микрофона: $n")
                        break
                    }
                }
            } catch (e: Exception) {
                if (running) log("Сбой потока: ${e.javaClass.simpleName}: ${e.message}")
            }
            if (running) {
                // цикл завершился сам (ошибка чтения, обрыв связи со шлемом)
                mode = Mode.IDLE
                log("Канал остановлен из-за ошибки (шлем отключился?). Нажмите кнопку режима заново.")
            }
        }.also { it.start() }
    }

    private fun stopWorker() {
        running = false
        try {
            rec?.stop()
        } catch (_: Exception) {
        }
        try {
            worker?.join(1500)
        } catch (_: InterruptedException) {
        }
        worker = null
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            ns?.release()
        } catch (_: Exception) {
        }
        ns = null
        rec?.release()
        track?.release()
        rec = null
        track = null
    }

    // ---------- обработка сигнала ----------

    /** Биквадратный фильтр (транспонированная прямая форма II). */
    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float
    ) {
        private var z1 = 0f
        private var z2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    /** Фильтр Баттерворта 2-го порядка (формулы RBJ, Q=0.7071) для частоты дискретизации RATE. */
    private fun biquad(highPass: Boolean, fc: Float): Biquad {
        val w0 = 2.0 * Math.PI * fc / RATE
        val cosW = Math.cos(w0).toFloat()
        val alpha = (Math.sin(w0) / (2.0 * 0.7071)).toFloat()
        val a0 = 1f + alpha
        val b0 = if (highPass) (1f + cosW) / 2f else (1f - cosW) / 2f
        val b1 = if (highPass) -(1f + cosW) else 1f - cosW
        return Biquad(b0 / a0, b1 / a0, b0 / a0, (-2f * cosW) / a0, (1f - alpha) / a0)
    }

    /** Мягкое ограничение: до 22000 без изменений, выше плавно приближается к 32767. */
    private fun softClip(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= 22000f) return x
        val over = (a - 22000f) / 10767f
        val s = 22000f + 10767f * (over / (1f + over))
        return if (x < 0f) -s else s
    }

    // ---------- журнал и публикация состояния ----------

    private fun name(d: AudioDeviceInfo?): String = when (d?.type) {
        null -> "нет"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "ВСТРОЕННЫЙ_МИК"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "ВСТРОЕННЫЙ_ДИНАМИК"
        else -> "тип=${d?.type}"
    }

    private fun log(s: String) {
        synchronized(logLines) {
            logLines.addLast(s)
            while (logLines.size > 14) logLines.removeFirst()
        }
        publish()
    }

    private fun publish() {
        val m = mode
        val l = currentLog()
        main.post { listener?.invoke(m, l) }
    }
}
