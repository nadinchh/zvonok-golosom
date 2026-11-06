package ru.nadin.zvonok

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.abs

/**
 * Микрофон, который пишет всё время, пока открыт экран звонка:
 *  - речь, сказанная раньше времени, не теряется (буфер до 6 с);
 *  - шумоподавление + усиление тихого голоса.
 */
class Mic(private val onText: (String) -> Unit, private val onTimeout: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var ar: AudioRecord? = null
    @Volatile private var running = false
    private var active = false          // принимать ли звук сейчас
    private var rec: Recognizer? = null // null = копить в буфер
    private val pending = ArrayDeque<ShortArray>()
    private var deadline = 0L
    private var level = 3000f

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val a = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (_: Exception) { null } ?: return false
        if (a.state != AudioRecord.STATE_INITIALIZED) { a.release(); return false }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(a.audioSessionId)?.enabled = true } catch (_: Exception) {}
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(a.audioSessionId)?.enabled = true } catch (_: Exception) {}
        ar = a; a.startRecording(); running = true
        Thread { loop(a) }.start()
        return true
    }

    /** Копить звук до готовности распознавания (папа может начать говорить сразу). */
    fun buffer() = synchronized(lock) { closeRec(); active = true }

    /** Начать распознавание; сначала скормить накопленное. */
    fun listen(r: Recognizer, timeoutMs: Long) = synchronized(lock) {
        closeRec(); rec = r; active = true
        deadline = System.currentTimeMillis() + timeoutMs
        while (pending.isNotEmpty()) { val b = pending.removeFirst(); if (feed(r, b, b.size)) return@synchronized }
    }

    /** Не слушать (пока телефон говорит сам). */
    fun pause() = synchronized(lock) { active = false; closeRec(); pending.clear() }

    fun release() {
        running = false; pause()
        try { ar?.stop() } catch (_: Exception) {}
        ar?.release(); ar = null
    }

    private fun closeRec() { try { rec?.close() } catch (_: Exception) {}; rec = null }

    private fun loop(a: AudioRecord) {
        val buf = ShortArray(1600) // 0,1 с
        while (running) {
            val n = a.read(buf, 0, buf.size)
            if (n <= 0) continue
            amplify(buf, n)
            synchronized(lock) {
                if (!active) return@synchronized
                val r = rec
                if (r == null) {
                    pending.addLast(buf.copyOf(n)); if (pending.size > 60) pending.removeFirst()
                } else if (!feed(r, buf, n) && System.currentTimeMillis() > deadline) {
                    val t = text(r.finalResult); active = false; closeRec()
                    main.post { if (t.isBlank()) onTimeout() else onText(t) }
                }
            }
        }
    }

    /** true — фраза распознана и отдана. */
    private fun feed(r: Recognizer, b: ShortArray, n: Int): Boolean {
        if (!r.acceptWaveForm(b, n)) return false
        val t = text(r.result)
        if (t.isBlank()) return false // была тишина/шум — слушаем дальше
        active = false; closeRec()
        main.post { onText(t) }
        return true
    }

    /** Мягкое усиление тихой речи (до ×4). */
    private fun amplify(b: ShortArray, n: Int) {
        var peak = 1
        for (i in 0 until n) { val v = abs(b[i].toInt()); if (v > peak) peak = v }
        level = maxOf(peak.toFloat(), level * 0.97f, 1500f)
        val g = (14000f / level).coerceIn(1f, 4f)
        if (g <= 1.01f) return
        for (i in 0 until n) b[i] = (b[i] * g).toInt().coerceIn(-32768, 32767).toShort()
    }

    private fun text(json: String?) = try {
        JSONObject(json ?: "{}").optString("text").replace("[unk]", "").trim()
    } catch (_: Exception) { "" }
}
