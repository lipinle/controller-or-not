package com.example.controllerornot

/**
 * Turns text into USB-HID keyboard reports (8 bytes: modifiers, reserved, 6 key codes).
 * English uses the US layout. Thai maps each character to the physical key it sits on in the
 * Thai Kedmanee layout, so the host must have its Thai keyboard active to show it correctly.
 */
object Keyboard {
    const val MOD_SHIFT = 0x02

    private class Key(val mod: Int, val code: Int)

    class Result(val reports: List<ByteArray>, val skipped: Int)

    private val us = HashMap<Char, Key>()
    private val thaiToUs = HashMap<Char, Char>()

    // Thai Kedmanee, listed key by key in the same order as the US characters beside them.
    private val US_ROWS = listOf(
        "`1234567890-=", "~!@#$%^&*()_+",
        "qwertyuiop[]\\", "QWERTYUIOP{}|",
        "asdfghjkl;'", "ASDFGHJKL:\"",
        "zxcvbnm,./", "ZXCVBNM<>?",
    )
    private val TH_ROWS = listOf(
        "_ๅ/-ภถุึคตจขช", "%+๑๒๓๔ู฿๕๖๗๘๙",
        "ๆไำพะัีรนยบลฃ", "๐\"ฎฑธํ๊ณฯญฐ,ฅ",
        "ฟหกดเ้่าสวง", "ฤฆฏโฌ็๋ษศซ.",
        "ผปแอิืทมใฝ", "()ฉฮฺ์?ฒฬฦ",
    )

    init {
        for (c in 'a'..'z') us[c] = Key(0, 0x04 + (c - 'a'))
        for (c in 'A'..'Z') us[c] = Key(MOD_SHIFT, 0x04 + (c - 'A'))
        "1234567890".forEachIndexed { i, c -> us[c] = Key(0, 0x1E + i) }
        "!@#$%^&*()".forEachIndexed { i, c -> us[c] = Key(MOD_SHIFT, 0x1E + i) }
        fun pair(base: Char, shifted: Char, code: Int) {
            us[base] = Key(0, code)
            us[shifted] = Key(MOD_SHIFT, code)
        }
        pair('-', '_', 0x2D); pair('=', '+', 0x2E); pair('[', '{', 0x2F); pair(']', '}', 0x30)
        pair('\\', '|', 0x31); pair(';', ':', 0x33); pair('\'', '"', 0x34); pair('`', '~', 0x35)
        pair(',', '<', 0x36); pair('.', '>', 0x37); pair('/', '?', 0x38)
        us[' '] = Key(0, 0x2C)
        us['\n'] = Key(0, 0x28)
        us['\t'] = Key(0, 0x2B)

        for (r in US_ROWS.indices) {
            val u = US_ROWS[r]
            val t = TH_ROWS[r]
            for (i in u.indices) thaiToUs[t[i]] = u[i]
        }
    }

    private val RELEASE = ByteArray(8)

    private fun report(mod: Int, code: Int): ByteArray {
        val b = ByteArray(8)
        b[0] = mod.toByte()
        b[2] = code.toByte()
        return b
    }

    /** Press + release of one HID usage code (used by the special-key buttons). */
    fun tap(code: Int, mod: Int = 0): List<ByteArray> = listOf(report(mod, code), RELEASE)

    fun reports(text: String, thai: Boolean): Result {
        val out = ArrayList<ByteArray>()
        var skipped = 0
        for (ch in text) {
            val key = when {
                ch == ' ' || ch == '\n' || ch == '\t' -> us[ch]
                thai -> thaiToUs[ch]?.let { us[it] }
                else -> us[ch]
            }
            if (key == null) { skipped++; continue }
            out.add(report(key.mod, key.code))
            out.add(RELEASE)
        }
        return Result(out, skipped)
    }

    // HID usage codes for the special-key buttons
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val DELETE = 0x4C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
}
