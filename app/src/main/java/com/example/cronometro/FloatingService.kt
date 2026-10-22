package com.example.cronometro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var timeText: TextView
    private lateinit var btnPlay: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var startAt = 0L   // SystemClock.elapsedRealtime() quando começou/continuou
    private var base = 0L      // tempo acumulado antes da última pausa

    private val ticker = object : Runnable {
        override fun run() {
            render()
            if (running) handler.postDelayed(this, 30)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        iniciarEmPrimeiroPlano()
        if (root == null) mostrarJanela()
        return START_NOT_STICKY
    }

    private fun iniciarEmPrimeiroPlano() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Cronômetro flutuante", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val fechar = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notificacao: Notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Cronômetro flutuante ativo")
            .setContentText("Toque para abrir o app")
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(abrir)
            .addAction(R.drawable.ic_close, "Fechar", fechar)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, notificacao,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun mostrarJanela() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        val v = LayoutInflater.from(this).inflate(R.layout.floating_stopwatch, null)
        root = v
        timeText = v.findViewById(R.id.timeText)
        btnPlay = v.findViewById(R.id.btnPlay)

        v.findViewById<ImageButton>(R.id.btnReset).setOnClickListener { zerar() }
        btnPlay.setOnClickListener { alternar() }
        v.findViewById<ImageButton>(R.id.btnRestart).setOnClickListener { reiniciar() }
        v.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { stopSelf() }

        val tipo = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            2002 // TYPE_PHONE, usado só em Android antigo

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 220
        }
        params = p

        // Arrastar a janela segurando em qualquer área que não seja botão.
        var iniX = 0
        var iniY = 0
        var toqueX = 0f
        var toqueY = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    iniX = p.x; iniY = p.y
                    toqueX = e.rawX; toqueY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = iniX + (e.rawX - toqueX).toInt()
                    p.y = iniY + (e.rawY - toqueY).toInt()
                    manager.updateViewLayout(v, p)
                    true
                }
                else -> false
            }
        }

        try {
            manager.addView(v, p)
        } catch (e: Exception) {
            // Sem a permissão "Exibir sobre outros apps" não dá para mostrar a janela.
            root = null
            stopSelf()
            return
        }
        atualizarIcone()
        render()
    }

    private fun tempoAgora(): Long =
        base + if (running) SystemClock.elapsedRealtime() - startAt else 0L

    private fun alternar() {
        if (running) {
            base += SystemClock.elapsedRealtime() - startAt
            running = false
            handler.removeCallbacks(ticker)
        } else {
            startAt = SystemClock.elapsedRealtime()
            running = true
            handler.post(ticker)
        }
        atualizarIcone()
        render()
    }

    private fun zerar() {
        running = false
        base = 0L
        handler.removeCallbacks(ticker)
        atualizarIcone()
        render()
    }

    private fun reiniciar() {
        zerar()
        alternar()
    }

    private fun atualizarIcone() {
        if (running) {
            btnPlay.setImageResource(R.drawable.ic_pause)
            btnPlay.contentDescription = "Pausar"
        } else {
            btnPlay.setImageResource(R.drawable.ic_play)
            btnPlay.contentDescription = "Iniciar"
        }
    }

    private fun render() {
        val ms = tempoAgora()
        val cs = (ms / 10) % 100
        val s = (ms / 1000) % 60
        val m = (ms / 60000) % 60
        val h = ms / 3600000
        val principal = if (h > 0)
            String.format("%d:%02d:%02d", h, m, s)
        else
            String.format("%02d:%02d", m, s)
        val centesimos = String.format(".%02d", cs)

        val texto = SpannableStringBuilder(principal).apply {
            val ini = length
            append(centesimos)
            setSpan(RelativeSizeSpan(0.55f), ini, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.parseColor("#FF6A4D")), ini, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        timeText.text = texto
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        root?.let { v ->
            try { wm?.removeView(v) } catch (e: Exception) { }
        }
        root = null
        super.onDestroy()
    }

    companion object {
        const val CANAL = "cronometro_flutuante"
        const val ACTION_STOP = "com.example.cronometro.STOP"
    }
}
