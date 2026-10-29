package com.example.cronometro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.ColorUtils

class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var timeText: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnReset: ImageButton
    private lateinit var btnRestart: ImageButton
    private lateinit var btnPalette: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var panel: View
    private lateinit var seekAlpha: SeekBar
    private lateinit var lblAlpha: TextView
    private lateinit var lblFundo: TextView
    private lateinit var lblNumeros: TextView
    private lateinit var lblDestaque: TextView
    private lateinit var tvRestaurar: TextView
    private lateinit var rowFundo: LinearLayout
    private lateinit var rowNumeros: LinearLayout
    private lateinit var rowDestaque: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var startAt = 0L   // SystemClock.elapsedRealtime() quando começou/continuou
    private var base = 0L      // tempo acumulado antes da última pausa

    // Aparência (salva no celular em SharedPreferences)
    private val prefs by lazy { getSharedPreferences("aparencia", MODE_PRIVATE) }
    private var corFundo = PADRAO_FUNDO
    private var corNumeros = PADRAO_NUMEROS
    private var corDestaque = PADRAO_DESTAQUE
    private var transparencia = PADRAO_TRANSPARENCIA // 0 = fundo sólido, 95 = quase invisível

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
        btnReset = v.findViewById(R.id.btnReset)
        btnRestart = v.findViewById(R.id.btnRestart)
        btnPalette = v.findViewById(R.id.btnPalette)
        btnClose = v.findViewById(R.id.btnClose)
        panel = v.findViewById(R.id.panel)
        seekAlpha = v.findViewById(R.id.seekAlpha)
        lblAlpha = v.findViewById(R.id.lblAlpha)
        lblFundo = v.findViewById(R.id.lblFundo)
        lblNumeros = v.findViewById(R.id.lblNumeros)
        lblDestaque = v.findViewById(R.id.lblDestaque)
        tvRestaurar = v.findViewById(R.id.tvRestaurar)
        rowFundo = v.findViewById(R.id.rowFundo)
        rowNumeros = v.findViewById(R.id.rowNumeros)
        rowDestaque = v.findViewById(R.id.rowDestaque)

        btnReset.setOnClickListener { zerar() }
        btnPlay.setOnClickListener { alternar() }
        btnRestart.setOnClickListener { reiniciar() }
        btnClose.setOnClickListener { stopSelf() }
        btnPalette.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Aparência salva anteriormente
        corFundo = prefs.getInt("fundo", PADRAO_FUNDO)
        corNumeros = prefs.getInt("numeros", PADRAO_NUMEROS)
        corDestaque = prefs.getInt("destaque", PADRAO_DESTAQUE)
        transparencia = prefs.getInt("transparencia", PADRAO_TRANSPARENCIA)

        seekAlpha.progress = transparencia
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progresso: Int, doUsuario: Boolean) {
                transparencia = progresso
                aplicarAparencia()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt("transparencia", transparencia).apply()
            }
        })

        tvRestaurar.setOnClickListener {
            corFundo = PADRAO_FUNDO
            corNumeros = PADRAO_NUMEROS
            corDestaque = PADRAO_DESTAQUE
            transparencia = PADRAO_TRANSPARENCIA
            prefs.edit().clear().apply()
            seekAlpha.progress = transparencia
            desenharPaleta()
            aplicarAparencia()
        }

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
        desenharPaleta()
        aplicarAparencia()
        atualizarIcone()
        render()
    }

    // ---------- Aparência ----------

    private fun aplicarAparencia() {
        val v = root ?: return
        val d = resources.displayMetrics.density

        // Fundo do cartão com a transparência escolhida
        val alfa = ((100 - transparencia) * 255) / 100
        val fundo = (corFundo and 0xFFFFFF) or (alfa shl 24)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22 * d
            setColor(fundo)
            setStroke(maxOf(1, d.toInt()), ColorUtils.setAlphaComponent(corNumeros, 60))
        }

        // Botões secundários: uma camada leve da cor dos números, funciona em qualquer fundo
        val secundario = ColorUtils.setAlphaComponent(corNumeros, 40)
        for (b in listOf(btnReset, btnRestart, btnPalette, btnClose)) {
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(secundario)
            }
            b.setColorFilter(corNumeros)
        }

        // Botão de play com a cor de destaque; o ícone escolhe preto ou branco para ter contraste
        btnPlay.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(corDestaque)
        }
        btnPlay.setColorFilter(
            if (ColorUtils.calculateLuminance(corDestaque) > 0.5) Color.BLACK else Color.WHITE
        )

        timeText.setTextColor(corNumeros)
        for (t in listOf(lblAlpha, lblFundo, lblNumeros, lblDestaque, tvRestaurar)) {
            t.setTextColor(corNumeros)
        }
        val tonalidade = ColorStateList.valueOf(corDestaque)
        seekAlpha.progressTintList = tonalidade
        seekAlpha.thumbTintList = tonalidade
        lblAlpha.text = "Transparência do fundo: $transparencia%"

        render()
    }

    private fun desenharPaleta() {
        montarLinha(rowFundo, CORES_FUNDO, corFundo) {
            corFundo = it
            prefs.edit().putInt("fundo", it).apply()
        }
        montarLinha(rowNumeros, CORES_NUMEROS, corNumeros) {
            corNumeros = it
            prefs.edit().putInt("numeros", it).apply()
        }
        montarLinha(rowDestaque, CORES_DESTAQUE, corDestaque) {
            corDestaque = it
            prefs.edit().putInt("destaque", it).apply()
        }
    }

    private fun montarLinha(
        linha: LinearLayout,
        cores: List<String>,
        atual: Int,
        aoEscolher: (Int) -> Unit
    ) {
        linha.removeAllViews()
        val d = resources.displayMetrics.density
        val tamanho = (32 * d).toInt()
        val margem = (5 * d).toInt()
        for (hex in cores) {
            val cor = Color.parseColor(hex)
            val selecionada = (atual and 0xFFFFFF) == (cor and 0xFFFFFF)
            val bolinha = TextView(this)
            val lp = LinearLayout.LayoutParams(tamanho, tamanho)
            lp.setMargins(margem, 0, margem, 0)
            bolinha.layoutParams = lp
            bolinha.gravity = Gravity.CENTER
            bolinha.textSize = 16f
            bolinha.setTypeface(null, Typeface.BOLD)
            bolinha.contentDescription = hex
            bolinha.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(cor)
                setStroke(maxOf(1, d.toInt()), Color.GRAY)
            }
            if (selecionada) {
                bolinha.text = "✓"
                bolinha.setTextColor(
                    if (ColorUtils.calculateLuminance(cor) > 0.5) Color.BLACK else Color.WHITE
                )
            }
            bolinha.setOnClickListener {
                aoEscolher(cor)
                desenharPaleta()
                aplicarAparencia()
            }
            linha.addView(bolinha)
        }
    }

    // ---------- Cronômetro ----------

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
        if (root == null) return
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
            setSpan(ForegroundColorSpan(corDestaque), ini, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

        val PADRAO_FUNDO: Int = Color.parseColor("#1B1D22")
        val PADRAO_NUMEROS: Int = Color.parseColor("#F2F1EE")
        val PADRAO_DESTAQUE: Int = Color.parseColor("#FF6A4D")
        const val PADRAO_TRANSPARENCIA = 6

        val CORES_FUNDO = listOf("#1B1D22", "#000000", "#FFFFFF", "#0F2A4A", "#1E3D2F", "#4A1F2E")
        val CORES_NUMEROS = listOf("#F2F1EE", "#FFFFFF", "#000000", "#FFD54A", "#7CF0C4", "#8CC8FF")
        val CORES_DESTAQUE = listOf("#FF6A4D", "#FFB300", "#4CC790", "#4DA3FF", "#B388FF", "#FF5C8A")
    }
}
