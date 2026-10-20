package ru.nadin.zvonok

import android.content.Context
import org.vosk.Model
import org.vosk.android.StorageService

/** Офлайн-модель распознавания русской речи (Vosk). Загружается один раз. */
object Speech {
    @Volatile var model: Model? = null
    private var loading = false
    private val waiters = mutableListOf<(Model?, String?) -> Unit>()

    fun get(ctx: Context, cb: (Model?, String?) -> Unit) {
        model?.let { cb(it, null); return }
        synchronized(this) {
            waiters += cb
            if (loading) return
            loading = true
        }
        StorageService.unpack(ctx.applicationContext, "model-ru", "model",
            { m -> model = m; flush(m, null) },
            { e -> flush(null, e.message ?: "ошибка модели") })
    }

    private fun flush(m: Model?, err: String?) {
        val list = synchronized(this) { loading = false; waiters.toList().also { waiters.clear() } }
        list.forEach { it(m, err) }
    }
}
