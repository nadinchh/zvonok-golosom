package ru.nadin.zvonok

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Главный экран: весь экран — одна кнопка «Позвонить голосом». Внизу — маленькая кнопка настроек для родных. */
class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val big = TextView(this).apply {
            text = "ПОЗВОНИТЬ\nГОЛОСОМ\n\nнажмите в любое место"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); setTextColor(Color.BLACK)
            setBackgroundColor(Color.YELLOW); gravity = Gravity.CENTER
            contentDescription = "Позвонить голосом. Нажмите, чтобы сказать, кому позвонить"
            setOnClickListener { startActivity(Intent(this@MainActivity, VoiceActivity::class.java)) }
        }
        val settings = Button(this).apply {
            text = "Настройки (для родных)"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            addView(big, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(settings, LinearLayout.LayoutParams(-1, -2))
        })
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 1)
        Speech.get(this) { _, _ -> } // заранее распаковать модель речи
    }
}
