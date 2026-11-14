package com.example.helmetrelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var btnListen: Button
    private lateinit var btnTalk: Button
    private lateinit var prefs: SharedPreferences

    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        status = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            text = "Остановлено"
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        btnListen = bigButton("1  СЛУШАТЬ\n(телефон → шлем)") { send(RelayService.ACTION_LISTEN) }
        btnTalk = bigButton("2  ГОВОРИТЬ\n(шлем → телефон)") { send(RelayService.ACTION_TALK) }
        root.addView(btnListen, LinearLayout.LayoutParams(-1, (150 * dp).toInt()).apply { topMargin = 16 })
        root.addView(btnTalk, LinearLayout.LayoutParams(-1, (150 * dp).toInt()).apply { topMargin = 16 })

        val btnStop = Button(this).apply {
            text = "СТОП"
            setOnClickListener { send(RelayService.ACTION_STOP) }
        }
        root.addView(btnStop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })

        // ---------- настройки режима 2 (шлем → телефон) ----------
        addHeader(root, "Режим 2: говорить")

        RelayService.talkAuto = prefs.getBoolean("auto", true)
        val autoIndex = root.childCount // галочка добавляется сюда, выше ползунков

        val gainStep = prefs.getInt("gainStep", 30).coerceIn(1, 60)
        RelayService.talkGain = gainStep.toFloat()
        val gainBox = addSlider(
            root, { "Усиление микрофона шлема (ручной режим): ×${it + 1}" },
            59, gainStep - 1
        ) {
            RelayService.talkGain = (it + 1).toFloat()
            prefs.edit().putInt("gainStep", it + 1).apply()
        }

        val targetStep = prefs.getInt("targetStep2", 10).coerceIn(0, 20)
        RelayService.talkTarget = 8000f + targetStep * 2000f
        val targetBox = addSlider(
            root,
            {
                val level = 8000 + it * 2000
                "Громкость (авто-режим): ${level * 100 / 32767}%" + (if (level > 32767) " (с перегрузом: громче, но с искажениями)" else "")
            },
            20, targetStep
        ) {
            RelayService.talkTarget = 8000f + it * 2000f
            prefs.edit().putInt("targetStep2", it).apply()
        }

        fun applyAutoVisibility() {
            gainBox.visibility = if (RelayService.talkAuto) android.view.View.GONE else android.view.View.VISIBLE
            targetBox.visibility = if (RelayService.talkAuto) android.view.View.VISIBLE else android.view.View.GONE
        }
        root.addView(CheckBox(this).apply {
            text = "Автоусиление (подстраивается под громкость голоса)"
            isChecked = RelayService.talkAuto
            setOnCheckedChangeListener { _, checked ->
                RelayService.talkAuto = checked
                prefs.edit().putBoolean("auto", checked).apply()
                applyAutoVisibility()
            }
        }, autoIndex, LinearLayout.LayoutParams(-1, -2))
        applyAutoVisibility()

        val talkGateStep = prefs.getInt("talkGateStep", 0).coerceIn(0, 60)
        RelayService.talkGate = talkGateStep * 25
        addSlider(
            root,
            { if (it == 0) "Порог тишины (против эха): выключен" else "Порог тишины (против эха): ${it * 25} (пик сигнала, ниже — тишина)" },
            60, talkGateStep
        ) {
            RelayService.talkGate = it * 25
            prefs.edit().putInt("talkGateStep", it).apply()
        }

        RelayService.talkEffects = prefs.getBoolean("talkEffects", true)
        root.addView(CheckBox(this).apply {
            text = "Эхо- и шумоподавление системы (применяется при следующем нажатии «2»)"
            isChecked = RelayService.talkEffects
            setOnCheckedChangeListener { _, checked ->
                RelayService.talkEffects = checked
                prefs.edit().putBoolean("talkEffects", checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        val sources = listOf(
            "Голосовой (VOICE_COMMUNICATION)" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "Обычный (MIC)" to MediaRecorder.AudioSource.MIC,
            "Распознавание (VOICE_RECOGNITION)" to MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        var srcIdx = prefs.getInt("srcIdx", 0).coerceIn(0, sources.size - 1)
        RelayService.talkSource = sources[srcIdx].second
        val srcBtn = Button(this)
        fun srcText() = "Источник микрофона шлема: ${sources[srcIdx].first}\n(применяется при следующем нажатии «2»)"
        srcBtn.text = srcText()
        srcBtn.setOnClickListener {
            srcIdx = (srcIdx + 1) % sources.size
            RelayService.talkSource = sources[srcIdx].second
            prefs.edit().putInt("srcIdx", srcIdx).apply()
            srcBtn.text = srcText()
        }
        root.addView(srcBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })

        // ---------- настройки режима 1 (телефон → шлем) ----------
        addHeader(root, "Режим 1: слушать")

        RelayService.listenFilter = prefs.getBoolean("lfilter", false)
        root.addView(CheckBox(this).apply {
            text = "Фильтр речи 300–3400 Гц"
            isChecked = RelayService.listenFilter
            setOnCheckedChangeListener { _, checked ->
                RelayService.listenFilter = checked
                prefs.edit().putBoolean("lfilter", checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        RelayService.listenNs = prefs.getBoolean("lns", false)
        root.addView(CheckBox(this).apply {
            text = "Шумоподавитель системы (применяется при следующем нажатии «1»)"
            isChecked = RelayService.listenNs
            setOnCheckedChangeListener { _, checked ->
                RelayService.listenNs = checked
                prefs.edit().putBoolean("lns", checked).apply()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        val listenGainStep = prefs.getInt("listenGainStep", 0).coerceIn(0, 10)
        RelayService.listenGain = 1f + listenGainStep * 0.5f
        addSlider(
            root, { "Усиление звука в шлеме: ×${1f + it * 0.5f}" },
            10, listenGainStep
        ) {
            RelayService.listenGain = 1f + it * 0.5f
            prefs.edit().putInt("listenGainStep", it).apply()
        }

        val gateStep = prefs.getInt("gateStep", 0).coerceIn(0, 48)
        RelayService.listenGate = gateStep * 250
        addSlider(
            root, { if (it == 0) "Порог тишины: выключен" else "Порог тишины: ${it * 250} (пик сигнала, ниже — тишина)" },
            48, gateStep
        ) {
            RelayService.listenGate = it * 250
            prefs.edit().putInt("gateStep", it).apply()
        }

        addHeader(root, "Журнал")
        logView = TextView(this).apply { textSize = 12f }
        root.addView(logView, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll, ViewGroup.LayoutParams(-1, -1))
        render(RelayService.mode, RelayService.currentLog())
    }

    override fun onStart() {
        super.onStart()
        RelayService.listener = { m, l -> render(m, l) }
        render(RelayService.mode, RelayService.currentLog())
    }

    override fun onStop() {
        RelayService.listener = null
        super.onStop()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        setOnClickListener { onClick() }
    }

    private fun addHeader(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 32 })
    }

    private fun addSlider(
        root: LinearLayout,
        label: (Int) -> String,
        max: Int,
        initial: Int,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply {
            textSize = 14f
            text = label(initial)
        }
        box.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })
        val bar = SeekBar(this)
        bar.max = max
        bar.progress = initial
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                tv.text = label(value)
                onChange(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        box.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(box, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun render(m: RelayService.Mode, lines: List<String>) {
        status.text = when (m) {
            RelayService.Mode.IDLE -> "Остановлено"
            RelayService.Mode.LISTEN -> "Режим 1: СЛУШАЮ (микрофон телефона → шлем)"
            RelayService.Mode.TALK -> "Режим 2: ГОВОРЮ (микрофон шлема → динамик телефона)"
        }
        btnListen.setBackgroundColor(if (m == RelayService.Mode.LISTEN) Color.rgb(76, 175, 80) else Color.LTGRAY)
        btnTalk.setBackgroundColor(if (m == RelayService.Mode.TALK) Color.rgb(255, 152, 0) else Color.LTGRAY)
        logView.text = lines.joinToString("\n")
    }

    // ---------- разрешения и запуск ----------

    private val needed = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private fun send(action: String) {
        if (action == RelayService.ACTION_STOP) {
            startService(Intent(this, RelayService::class.java).setAction(action))
            return
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, RelayService::class.java).setAction(action))
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val action = pendingAction ?: return
        pendingAction = null
        // уведомления не критичны; обязательны микрофон и Bluetooth
        val ok = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (ok) {
            startForegroundService(Intent(this, RelayService::class.java).setAction(action))
        } else {
            status.text = "Нужны разрешения: микрофон и Bluetooth (устройства поблизости)"
        }
    }
}
