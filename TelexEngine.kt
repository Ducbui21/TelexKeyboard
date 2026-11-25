package com.example.telexkeyboard

/** Biến chuỗi phím Telex thô (vd "vieetj") thành chữ tiếng Việt ("việt"). */
object TelexEngine {
    private const val VOWELS = "aăâeêioôơuưy"
    private const val TONE_KEYS = "sfrxj" // sắc, huyền, hỏi, ngã, nặng
    private val TONE_TABLE = listOf(
        "aáàảãạ", "ăắằẳẵặ", "âấầẩẫậ", "eéèẻẽẹ", "êếềểễệ", "iíìỉĩị",
        "oóòỏõọ", "ôốồổỗộ", "ơớờởỡợ", "uúùủũụ", "ưứừửữự", "yýỳỷỹỵ"
    )

    private fun isVowel(c: Char) = c.lowercaseChar() in VOWELS

    private fun withCase(src: Char, lower: Char): Char =
        if (src.isUpperCase()) lower.uppercaseChar() else lower

    /** Nguyên âm cuối của từ; trả về -1 nếu nguyên âm đó không thuộc [set]. */
    private fun findTarget(l: List<Char>, set: String): Int {
        for (i in l.indices.reversed()) {
            val lc = l[i].lowercaseChar()
            if (isVowel(lc)) return if (lc in set) i else -1
        }
        return -1
    }

    fun compose(raw: String): String {
        val l = ArrayList<Char>()
        var tone = 0
        for (ch in raw) {
            val c = ch.lowercaseChar()
            when {
                // dd -> đ; gõ thêm d lần nữa để hủy
                c == 'd' && l.isNotEmpty() && l.last().lowercaseChar() == 'd' -> {
                    l[l.size - 1] = withCase(l.last(), 'đ')
                }
                c == 'd' && l.isNotEmpty() && l.last().lowercaseChar() == 'đ' -> {
                    l[l.size - 1] = withCase(l.last(), 'd')
                    l.add(ch)
                }
                // aa -> â, ee -> ê, oo -> ô; gõ lần nữa để hủy
                c == 'a' || c == 'e' || c == 'o' -> {
                    val circ = when (c) { 'a' -> 'â'; 'e' -> 'ê'; else -> 'ô' }
                    val i = findTarget(l, "$c$circ")
                    if (i < 0) {
                        l.add(ch)
                    } else if (l[i].lowercaseChar() == c) {
                        l[i] = withCase(l[i], circ)
                    } else {
                        l[i] = withCase(l[i], c)
                        l.add(ch)
                    }
                }
                // w -> ă, ơ, ư (kèm ươ, ưa)
                c == 'w' -> {
                    val i = findTarget(l, "aăoơuư")
                    if (i < 0) {
                        l.add(withCase(ch, 'ư'))
                    } else {
                        val prevU = i > 0 && l[i - 1].lowercaseChar() == 'u' &&
                                !(i > 1 && l[i - 2].lowercaseChar() == 'q')
                        val prevHorn = i > 0 && l[i - 1].lowercaseChar() == 'ư'
                        when (l[i].lowercaseChar()) {
                            'a' -> {
                                if (prevU) {
                                    l[i - 1] = withCase(l[i - 1], 'ư')
                                } else {
                                    l[i] = withCase(l[i], 'ă')
                                }
                            }
                            'ă' -> {
                                l[i] = withCase(l[i], 'a')
                                l.add(ch)
                            }
                            'o' -> {
                                if (prevU) l[i - 1] = withCase(l[i - 1], 'ư')
                                l[i] = withCase(l[i], 'ơ')
                            }
                            'ơ' -> {
                                if (prevHorn) l[i - 1] = withCase(l[i - 1], 'u')
                                l[i] = withCase(l[i], 'o')
                                l.add(ch)
                            }
                            'u' -> l[i] = withCase(l[i], 'ư')
                            'ư' -> {
                                l[i] = withCase(l[i], 'u')
                                l.add(ch)
                            }
                        }
                    }
                }
                // dấu thanh s f r x j (gõ lặp để hủy); z để xóa dấu
                c in TONE_KEYS && l.any { isVowel(it) } && l.last().lowercaseChar() != c -> {
                    val t = TONE_KEYS.indexOf(c) + 1
                    if (tone == t) {
                        tone = 0
                        l.add(ch)
                    } else {
                        tone = t
                    }
                }
                c == 'z' && tone != 0 -> tone = 0
                else -> l.add(ch)
            }
        }
        if (tone != 0) {
            val idx = toneIndex(l)
            if (idx >= 0) l[idx] = applyTone(l[idx], tone)
        }
        return String(l.toCharArray())
    }

    private fun applyTone(c: Char, tone: Int): Char {
        val row = TONE_TABLE.firstOrNull { it[0] == c.lowercaseChar() } ?: return c
        return withCase(c, row[tone])
    }

    /** Chọn nguyên âm đặt dấu theo quy tắc chính tả hiện đại. */
    private fun toneIndex(l: List<Char>): Int {
        val n = l.size
        var start = 0
        if (n >= 2 && l[0].lowercaseChar() == 'q' && l[1].lowercaseChar() == 'u') {
            start = 2
        } else if (n >= 3 && l[0].lowercaseChar() == 'g' && l[1].lowercaseChar() == 'i' && isVowel(l[2])) {
            start = 2
        }
        var i = start
        while (i < n && !isVowel(l[i])) i++
        if (i >= n) return -1
        var j = i
        while (j < n && isVowel(l[j])) j++
        val len = j - i
        if (len == 1) return i
        for (k in j - 1 downTo i) {
            if (l[k].lowercaseChar() in "âăêôơư") return k
        }
        if (len >= 3) return i + 1
        if (j < n) return i + 1 // có phụ âm cuối: hoàn, tuyến...
        val pair = "${l[i].lowercaseChar()}${l[i + 1].lowercaseChar()}"
        return if (pair == "oa" || pair == "oe" || pair == "uy") i + 1 else i
    }
}
