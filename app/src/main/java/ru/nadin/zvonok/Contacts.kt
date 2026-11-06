package ru.nadin.zvonok

import android.content.Context
import android.provider.ContactsContract

data class Person(val name: String, val phone: String, val keys: Set<String>)

object Contacts {
    private val endings = listOf("ами","ями","ого","его","ому","ему","ой","ей","ом","ем","ам","ям","ах","ях","ую","юю","е","у","ы","и","а","я","ю","ь","й","о")
    private val filler = setOf("позвони","позвонить","набери","набрать","звони","звонок","вызови","вызвать","пожалуйста","мне","моей","моему","моя","мой","мою","на","к","ко","с","в","давай","алло","нужно","надо","хочу")

    fun stem(w: String): String {
        val s = w.lowercase().replace('ё', 'е')
        for (e in endings) if (s.length - e.length >= 2 && s.endsWith(e)) return s.dropLast(e.length)
        return s
    }

    fun words(text: String): List<String> =
        text.lowercase().replace('ё', 'е').split(Regex("[^а-яa-z0-9]+")).filter { it.isNotBlank() && it !in filler }

    /** Все контакты с телефонами + слова-подсказки из настроек ("жена = Таня"). */
    fun load(ctx: Context): List<Person> {
        val list = mutableListOf<Person>()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val phone = c.getString(1) ?: continue
                if (!seen.add(name)) continue
                list += Person(name, phone, words(translit(name)).map { stem(it) }.toSet())
            }
        }
        // Подсказки: "жена = Татьяна" или "такси = 88001234567"
        for (line in Aliases.get(ctx)) {
            val (alias, target) = line
            val aliasKeys = words(alias).map { stem(it) }.toSet()
            val digits = target.filter { it.isDigit() || it == '+' }
            val byName = list.firstOrNull { it.name.equals(target, true) }
                ?: list.firstOrNull { p -> words(target).map { stem(it) }.all { it in p.keys } }
            when {
                byName != null -> list += Person(byName.name, byName.phone, aliasKeys)
                digits.length >= 3 -> list += Person(alias, digits, aliasKeys)
            }
        }
        return list
    }

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }

    /** Лучший контакт для фразы или null. */
    fun match(text: String, people: List<Person>): Person? {
        val spoken = words(text).map { stem(it) }
        if (spoken.isEmpty()) return null
        var best: Person? = null; var bestScore = 0.0
        for (p in people) {
            var score = 0.0
            for (k in p.keys) {
                val s = spoken.maxOfOrNull { w ->
                    when {
                        w == k -> 1.0
                        k.length >= 3 && lev(w, k) == 1 -> 0.7
                        else -> 0.0
                    }
                } ?: 0.0
                score += s
            }
            // подсказки ("жена") важнее совпадения по одному слову имени
            if (p.keys.size == 1 && score >= 1.0) score += 0.5
            if (score > bestScore) { bestScore = score; best = p }
        }
        return if (bestScore >= 0.7) best else null
    }

    val yesWords = setOf("да", "ага", "звони", "давай", "верно", "конечно", "угу", "точно", "можно")
    val noWords = setOf("нет", "не", "отмена", "стоп", "другому", "отбой")
    private fun raw(t: String) = t.lowercase().replace('ё', 'е').split(Regex("[^а-яa-z0-9]+"))
    /** Имя контакта по номеру (для входящих). */
    fun nameFor(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        return try {
            ctx.contentResolver.query(
                android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null }
    }

    fun isNo(t: String) = raw(t).any { it in noWords }
    fun isYes(t: String) = !isNo(t) && raw(t).any { it in yesWords }

    private val lat = listOf("shch" to "щ", "sch" to "щ", "sh" to "ш", "ch" to "ч", "zh" to "ж", "kh" to "х", "ts" to "ц",
        "ya" to "я", "yu" to "ю", "yo" to "е", "ia" to "я", "a" to "а", "b" to "б", "v" to "в", "w" to "в", "g" to "г", "d" to "д",
        "e" to "е", "z" to "з", "i" to "и", "y" to "й", "k" to "к", "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п",
        "r" to "р", "s" to "с", "t" to "т", "u" to "у", "f" to "ф", "h" to "х", "c" to "к", "x" to "кс", "q" to "к", "j" to "дж")
    /** Латинские имена (Mama, Sasha) → кириллица, чтобы их можно было назвать голосом. */
    fun translit(name: String): String {
        var s = name.lowercase()
        for ((a, b) in lat) s = s.replace(a, b)
        return s
    }

    /** Падежные формы слова: Оля → Оле, Олю, Оли… (для словаря распознавания). */
    private fun forms(w: String): Set<String> {
        val out = mutableSetOf(w)
        if (w.length < 2) return out
        val b = w.dropLast(1)
        when (w.last()) {
            'а' -> out += listOf(b + "е", b + "у", b + "ы", b + "и", b + "ой")
            'я' -> out += listOf(b + "е", b + "ю", b + "и", b + "ей")
            'й', 'ь' -> out += listOf(b + "ю", b + "я", b + "е", b + "и", b + "ем")
            'о', 'е', 'и', 'у', 'ы', 'ю', 'э' -> {}
            else -> out += listOf(w + "у", w + "а", w + "е", w + "ом")
        }
        return out
    }

    /** Словарь для распознавания: только имена из телефона + служебные слова. Сильно повышает точность. */
    fun grammarWho(ctx: Context, people: List<Person>): String {
        val ws = mutableSetOf<String>()
        for (p in people) words(translit(p.name)).forEach { ws += forms(it) }
        for ((a, _) in Aliases.get(ctx)) words(a).forEach { ws += forms(it) }
        ws += filler; ws += noWords; ws += Info.words
        return org.json.JSONArray((ws.filter { it.all { c -> c in 'а'..'я' } } + "[unk]")).toString()
    }

    fun grammarConfirm(): String = org.json.JSONArray((yesWords + noWords + "[unk]").toList()).toString()
}

object Aliases {
    private const val KEY = "aliases"
    val default = "жена = \nдочка = \nНадин = \nтакси = \nработа = "
    fun raw(ctx: Context): String = ctx.getSharedPreferences("p", 0).getString(KEY, default) ?: default
    fun save(ctx: Context, s: String) = ctx.getSharedPreferences("p", 0).edit().putString(KEY, s).apply()
    fun get(ctx: Context): List<Pair<String, String>> = raw(ctx).lines().mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else {
            val a = it.substring(0, i).trim(); val b = it.substring(i + 1).trim()
            if (a.isEmpty() || b.isEmpty()) null else a to b
        }
    }
}
