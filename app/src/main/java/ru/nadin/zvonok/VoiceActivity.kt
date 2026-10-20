package ru.nadin.zvonok

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale

class VoiceActivity : Activity(), RecognitionListener {
    private enum class Step { WHO, CONFIRM }

    private lateinit var tv: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speech: SpeechService? = null
    private var step = Step.WHO
    private var people: List<Person> = emptyList()
    private var candidate: Person? = null
    private var misses = 0
    private val h = Handler(Looper.getMainLooper())
    private var pendingListen = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tv = TextView(this).apply {
            setBackgroundColor(Color.BLACK); setTextColor(Color.YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40); text = "Загружаю…"
            setOnClickListener { startOver() }
        }
        setContentView(tv)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        people = Contacts.load(this)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU"); tts?.setSpeechRate(0.95f); ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) { onSpoken(id) }
                    override fun onDone(id: String?) { onSpoken(id) }
                })
            }
            Speech.get(this) { m, err ->
                runOnUiThread {
                    if (m == null) { show("Ошибка распознавания: $err") ; say("Не получилось включить распознавание речи", "end") }
                    else ask()
                }
            }
        }
    }

    private fun show(s: String) { tv.text = s }

    private fun say(text: String, id: String) {
        show(text)
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        else h.postDelayed({ onSpoken(id) }, 1200)
    }

    private fun onSpoken(id: String?) = runOnUiThread {
        when (id) {
            "listen" -> listen()
            "end" -> finish()
            "call" -> candidate?.let { call(it) }
        }
    }

    private fun ask() { step = Step.WHO; say("Кому позвонить?", "listen") }

    private fun startOver() { stopListening(); misses = 0; candidate = null; ask() }

    private fun listen() {
        val m = Speech.model ?: return
        stopListening()
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(80, 120))
        } catch (_: Exception) {}
        h.postDelayed({
            try {
                speech = SpeechService(Recognizer(m, 16000.0f), 16000.0f).also { it.startListening(this, 7000) }
                show(if (step == Step.WHO) "Слушаю: кому позвонить?" else "Позвонить ${candidate?.name}?\nДа или нет?")
            } catch (e: Exception) { say("Микрофон не работает", "end") }
        }, 250)
    }

    private fun stopListening() { speech?.stop(); speech?.shutdown(); speech = null }

    private fun heard(text: String) {
        stopListening()
        if (text.isBlank()) { miss(); return }
        when (step) {
            Step.WHO -> {
                val p = Contacts.match(text, people)
                if (p == null) { show("Услышала: $text"); miss(); return }
                misses = 0; candidate = p; step = Step.CONFIRM
                say("Позвонить ${p.name}?", "listen")
            }
            Step.CONFIRM -> when {
                Contacts.isYes(text) -> say("Звоню ${candidate?.name}", "call")
                Contacts.isNo(text) -> { candidate = null; step = Step.WHO; say("Хорошо. Кому позвонить?", "listen") }
                else -> { misses++; if (misses >= 3) say("Не поняла. До свидания", "end") else say("Скажите да или нет. Позвонить ${candidate?.name}?", "listen") }
            }
        }
    }

    private fun miss() {
        misses++
        if (misses >= 3) say("Не расслышала. Нажмите кнопку ещё раз. До свидания", "end")
        else say("Не расслышала. Повторите, кому позвонить", "listen")
    }

    private fun call(p: Person) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(p.phone))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { say("Не получилось позвонить", "end"); return }
        finish()
    }

    private fun text(json: String?): String = try { JSONObject(json ?: "{}").optString("text") } catch (_: Exception) { "" }

    override fun onResult(hypothesis: String?) { val t = text(hypothesis); if (t.isNotBlank()) runOnUiThread { heard(t) } }
    override fun onFinalResult(hypothesis: String?) { val t = text(hypothesis); runOnUiThread { if (speech != null) heard(t) } }
    override fun onPartialResult(hypothesis: String?) {}
    override fun onError(e: Exception?) { runOnUiThread { say("Ошибка микрофона", "end") } }
    override fun onTimeout() { runOnUiThread { if (speech != null) heard("") } }

    override fun onDestroy() { stopListening(); tts?.shutdown(); super.onDestroy() }
}
