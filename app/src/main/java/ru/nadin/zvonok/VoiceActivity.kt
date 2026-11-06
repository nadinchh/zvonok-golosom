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
import android.telecom.TelecomManager
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.vosk.Recognizer
import java.util.Locale

class VoiceActivity : Activity() {
    private enum class Step { WHO, CONFIRM }

    private lateinit var tv: TextView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mic: Mic? = null
    private var step = Step.WHO
    private var people: List<Person> = emptyList()
    private var candidate: Person? = null
    private var misses = 0
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tv = TextView(this).apply {
            setBackgroundColor(Color.BLACK); setTextColor(Color.YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40); text = "Говорите: кому позвонить?"
            setOnClickListener { startOver() }
        }
        setContentView(tv)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish(); return
        }
        // Микрофон включаем сразу — папа может начать говорить, не дожидаясь вопроса.
        mic = Mic({ heard(it) }, { heard("") })
        beep()
        if (mic?.start() != true) { say("Микрофон не работает", "end") }
        mic?.buffer()
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
        }
        Speech.get(this) { m, err ->
            runOnUiThread {
                if (m == null) say("Не получилось включить распознавание речи", "end")
                else { step = Step.WHO; startRecognizer() }
            }
        }
    }

    private fun show(s: String) { tv.text = s }

    private fun say(text: String, id: String) {
        mic?.pause()
        show(text)
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        else h.postDelayed({ onSpoken(id) }, 1500)
    }

    private fun onSpoken(id: String?) = runOnUiThread {
        when (id) {
            "listen" -> listen()
            "end" -> finish()
            "call" -> candidate?.let { call(it) }
        }
    }

    private fun beep() = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 90).startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(80, 150))
    } catch (_: Exception) {}

    private fun startOver() { misses = 0; candidate = null; step = Step.WHO; say("Кому позвонить?", "listen") }

    /** Сигнал и сразу слушаем. */
    private fun listen() {
        beep()
        h.postDelayed({ startRecognizer() }, 150)
    }

    private fun startRecognizer() {
        val m = Speech.model ?: return
        val rec = try {
            Recognizer(m, 16000.0f, if (step == Step.WHO) Contacts.grammarWho(this, people) else Contacts.grammarConfirm())
        } catch (_: Exception) { Recognizer(m, 16000.0f) }
        mic?.listen(rec, 8000)
        show(if (step == Step.WHO) "Слушаю: кому позвонить?" else "Позвонить ${candidate?.name}?\nДа или нет?")
    }

    private fun heard(text: String) {
        val clean = text.replace("[unk]", "").trim()
        if (clean.isBlank()) { miss(); return }
        when (step) {
            Step.WHO -> {
                Info.answer(this, clean)?.let { say("$it. Кому позвонить?", "listen"); return }
                // сказал только «позвони…» и задумался — слушаем дальше без сигнала
                if (Contacts.words(clean).isEmpty()) { startRecognizer(); return }
                val p = Contacts.match(clean, people)
                if (p == null) { miss("Услышала: $clean."); return }
                misses = 0; candidate = p; step = Step.CONFIRM
                say("Позвонить ${p.name}?", "listen")
            }
            Step.CONFIRM -> when {
                Contacts.isNo(clean) -> { candidate = null; step = Step.WHO; say("Хорошо. Кому позвонить?", "listen") }
                Contacts.isYes(clean) -> say("Звоню ${candidate?.name}", "call")
                else -> { misses++; if (misses >= 3) say("Не поняла. До свидания", "end") else say("Скажите да или нет. Позвонить ${candidate?.name}?", "listen") }
            }
        }
    }

    private fun miss(prefix: String = "Не расслышала.") {
        misses++
        if (misses >= 3) say("$prefix Нажмите кнопку ещё раз. До свидания", "end")
        else say(if (step == Step.WHO) "$prefix Повторите, кому позвонить" else "$prefix Да или нет?", "listen")
    }

    private fun call(p: Person) {
        mic?.release(); mic = null
        try {
            Loud.apply(this)
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(p.phone)))
                .putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, Prefs.speaker(this))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { say("Не получилось позвонить", "end"); return }
        finish()
    }

    override fun onDestroy() { mic?.release(); tts?.shutdown(); super.onDestroy() }
}
