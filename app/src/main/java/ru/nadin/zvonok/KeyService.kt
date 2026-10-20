package ru.nadin.zvonok

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Долгое нажатие «громкость вниз» (0,8 с) запускает голосовой звонок. Короткое — обычная громкость. */
class KeyService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var longFired = false
    private var down = false
    private val launch = Runnable {
        longFired = true
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (!down) { down = true; longFired = false; h.postDelayed(launch, 800) }
            KeyEvent.ACTION_UP -> {
                down = false
                h.removeCallbacks(launch)
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
