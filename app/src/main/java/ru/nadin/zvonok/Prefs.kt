package ru.nadin.zvonok

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("p", 0)
    fun speaker(ctx: Context) = p(ctx).getBoolean("speaker", true)
    fun setSpeaker(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("speaker", v).apply()
    fun announce(ctx: Context) = p(ctx).getBoolean("announce", true)
    fun setAnnounce(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("announce", v).apply()
}

/** Громкость разговора на максимум и (если включено) громкая связь. */
object Loud {
    @Suppress("DEPRECATION")
    fun apply(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) } catch (_: Exception) {}
        if (Prefs.speaker(ctx)) try { am.isSpeakerphoneOn = true } catch (_: Exception) {}
    }
}

/** Ответы «сколько времени», «какое число», «батарея». */
object Info {
    private fun plural(n: Int, one: String, few: String, many: String): String {
        val a = n % 100; val b = n % 10
        return if (a in 11..14) many else when (b) { 1 -> one; 2, 3, 4 -> few; else -> many }
    }

    fun answer(ctx: Context, text: String): String? {
        val w = Contacts.words(text).toSet() + text.lowercase().split(" ")
        val c = Calendar.getInstance()
        return when {
            w.any { it in setOf("время", "времени", "час", "часов", "который") } -> {
                val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
                "Сейчас $h ${plural(h, "час", "часа", "часов")} $m ${plural(m, "минута", "минуты", "минут")}"
            }
            w.any { it in setOf("число", "дата", "день", "сегодня", "какое") } ->
                "Сегодня " + SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())
            w.any { it in setOf("батарея", "батарейка", "заряд", "зарядка", "аккумулятор") } -> {
                val n = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                "Заряд $n ${plural(n, "процент", "процента", "процентов")}" + if (n < 20) ". Поставьте телефон на зарядку" else ""
            }
            else -> null
        }
    }

    val words = listOf("сколько", "время", "времени", "час", "который", "какое", "число", "дата", "день", "сегодня",
        "батарея", "батарейка", "заряд", "зарядка", "аккумулятор")
}
