package ru.nadin.zvonok

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Настройки для родных: подсказки «слово = контакт» и включение кнопки громкости. */
class SettingsActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun label(s: String, size: Float = 16f) = TextView(this).apply { text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, pad / 2, 0, pad / 2) }

        col.addView(label("1. Кнопка громкости", 20f))
        col.addView(label("Включите службу «Звонок голосом: кнопка громкости» в Специальных возможностях. После этого долгое нажатие «громкость вниз» (около секунды) запускает голосовой звонок, даже на заблокированном экране."))
        col.addView(Button(this).apply { text = "Открыть специальные возможности"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })

        col.addView(label("2. Подсказки «слово = кому звонить»", 20f))
        col.addView(label("По одной строке: слово, которое скажет папа, знак «=» и имя контакта как в телефоне (или номер). Например:\nжена = Татьяна\nтакси = 88002000000\nНадин = Надя дочка"))
        val edit = EditText(this).apply {
            setText(Aliases.raw(this@SettingsActivity)); minLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        col.addView(edit)
        col.addView(Button(this).apply { text = "Сохранить"
            setOnClickListener {
                Aliases.save(this@SettingsActivity, edit.text.toString())
                val n = Contacts.load(this@SettingsActivity).size
                Toast.makeText(this@SettingsActivity, "Сохранено. Контактов с номерами: $n", Toast.LENGTH_LONG).show()
            } })

        col.addView(label("3. Голос", 20f))
        col.addView(label("Чтобы телефон говорил без интернета, в настройках «Синтез речи» выберите русский язык и скачайте офлайн-голос (Google или другой движок). Распознавание речи уже встроено в приложение и работает без интернета."))
        col.addView(Button(this).apply { text = "Открыть настройки синтеза речи"
            setOnClickListener { try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) } } })

        col.addView(label("4. Проверка", 20f))
        col.addView(Button(this).apply { text = "Проверить: сказать, кому позвонить"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, VoiceActivity::class.java)) } })

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
