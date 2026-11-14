package com.example.helmetrelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var btnListen: Button
    private lateinit var btnTalk: Button
    private lateinit var btnStop: Button

    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        root.addView(btnListen, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 16 })
        root.addView(btnTalk, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 16 })

        btnStop = Button(this).apply {
            text = "СТОП"
            setOnClickListener { send(RelayService.ACTION_STOP) }
        }
        root.addView(btnStop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })

        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val gainStep = prefs.getInt("gainStep", 4) // усиление = шаг (×1 … ×15)
        RelayService.talkGain = gainStep.toFloat()
        val gainLabel = TextView(this).apply {
            textSize = 14f
            text = "Усиление микрофона шлема в режиме 2: ×$gainStep"
        }
        root.addView(gainLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })
        val gainBar = SeekBar(this).apply {
            max = 14
            progress = gainStep - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val step = value + 1
                    RelayService.talkGain = step.toFloat()
                    gainLabel.text = "Усиление микрофона шлема в режиме 2: ×$step"
                    prefs.edit().putInt("gainStep", step).apply()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(gainBar, LinearLayout.LayoutParams(-1, -2))

        logView = TextView(this).apply { textSize = 12f }
        val scroll = ScrollView(this)
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 0.8f).apply { topMargin = 16 })

        setContentView(root, ViewGroup.LayoutParams(-1, -1))
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
