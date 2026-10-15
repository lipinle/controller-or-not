package com.example.controllerornot

/** Snapshot of every control on the virtual pad. Sticks are -1..1 (Y positive = down), triggers 0..1. */
data class GamepadState(
    val lx: Float = 0f, val ly: Float = 0f,
    val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f,
    val buttons: Int = 0,
    val hat: Int = HAT_NEUTRAL,
) {
    companion object {
        // Bit positions inside the 15-bit button field (HID buttons 1..15 -> bits 0..14),
        // following the Xbox wireless controller layout.
        const val A = 1 shl 0
        const val B = 1 shl 1
        const val X = 1 shl 3
        const val Y = 1 shl 4
        const val LB = 1 shl 6
        const val RB = 1 shl 7
        const val VIEW = 1 shl 10
        const val MENU = 1 shl 11
        const val GUIDE = 1 shl 12
        const val LS = 1 shl 13
        const val RS = 1 shl 14
        const val SHARE = 1 shl 15 // not part of the 15 regular buttons: sent as the Share (Record) bit

        // Hat switch: 0 = released, 1 = up, then clockwise in 45 degree steps (2 = up-right ... 8 = up-left).
        const val HAT_NEUTRAL = 0
    }
}
