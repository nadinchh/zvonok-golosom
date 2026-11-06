package ru.nadin.zvonok

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.util.Locale

/**
 * Долгое нажатие «громкость вниз» (0,8 с):
 *  - телефон звонит → ответить;
 *  - идёт разговор → положить трубку;
 *  - иначе → голосовой звонок.
 * Короткое нажатие — обычная громкость.
 * Входящий: телефон называет, кто звонит. После разговора говорит «Разговор окончен».
 */
class KeyService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var longFired = false
    private var down = false
    private var tts: TextToSpeech? = null
    private var state = TelephonyManager.EXTRA_STATE_IDLE
    private var incomingName: String? = null

    private val longPress = Runnable {
        longFired = true
        buzz()
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> answer()
            TelephonyManager.EXTRA_STATE_OFFHOOK -> hangUp()
            else -> startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    private val announce = object : Runnable {
        override fun run() {
            if (state != TelephonyManager.EXTRA_STATE_RINGING) return
            say("Звонит ${incomingName ?: "незнакомый номер"}. Чтобы ответить, зажмите громкость вниз")
            h.postDelayed(this, 6000)
        }
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val prev = state
            when (st) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (number != null) incomingName = Contacts.nameFor(c, number) ?: spellNumber(number)
                    if (prev != st && Prefs.announce(c)) { state = st; h.removeCallbacks(announce); h.postDelayed(announce, 1500) }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    h.removeCallbacks(announce); tts?.stop()
                    h.postDelayed({ Loud.apply(c) }, 800)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    h.removeCallbacks(announce)
                    if (prev == TelephonyManager.EXTRA_STATE_OFFHOOK) h.postDelayed({ say("Разговор окончен") }, 700)
                    incomingName = null
                }
            }
            state = st
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU"); tts?.setSpeechRate(0.85f)
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(phoneReceiver, f)
    }

    override fun onDestroy() {
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        tts?.shutdown(); super.onDestroy()
    }

    private fun say(s: String) {
        val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        tts?.speak(s, TextToSpeech.QUEUE_FLUSH, b, "k")
    }

    private fun spellNumber(n: String) = "номер " + n.takeLast(4).toList().joinToString(" ")

    private fun buzz() = try {
        (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(150, 255))
    } catch (_: Exception) {}

    private fun allowed() = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun answer() {
        h.removeCallbacks(announce); tts?.stop()
        if (!allowed()) { say("Нет разрешения отвечать на звонки"); return }
        try { (getSystemService(TELECOM_SERVICE) as TelecomManager).acceptRingingCall() } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun hangUp() {
        if (!allowed() || Build.VERSION.SDK_INT < 28) { say("Не могу положить трубку. Нажмите кнопку питания"); return }
        val ok = try { (getSystemService(TELECOM_SERVICE) as TelecomManager).endCall() } catch (_: Exception) { false }
        if (!ok) say("Не могу положить трубку. Нажмите кнопку питания")
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (!down) { down = true; longFired = false; h.postDelayed(longPress, 800) }
            KeyEvent.ACTION_UP -> {
                down = false
                h.removeCallbacks(longPress)
                if (!longFired) {
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    am.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                }
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
