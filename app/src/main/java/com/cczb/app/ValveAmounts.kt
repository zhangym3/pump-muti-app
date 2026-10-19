package com.cczb.app

/** Compact slot-indexed plan: exactly 32 bytes, each in units of 50 mL. */
object ValveAmounts {
    fun slots(mask: Long) = (1..32).filter { mask and (1L shl (it - 1)) != 0L }

    fun encode(mask: Long, values: List<String>): String? {
        if (mask !in 1..0xffff_ffffL || values.size != 32) return null
        val selected = slots(mask)
        if (selected.any { values[it - 1].toLongOrNull()?.let(VolumeRules::isValid) != true }) return null
        return (1..32).joinToString("") { slot ->
            val units = if (slot in selected) values[slot - 1].toLong() / VolumeRules.STEP_ML else 0
            units.toString(16).padStart(2, '0')
        }
    }

    fun isValid(mask: Long, encoded: String): Boolean {
        if (mask !in 1..0xffff_ffffL || !encoded.matches(Regex("[0-9a-f]{64}"))) return false
        return (1..32).all { slot ->
            val units = encoded.substring((slot - 1) * 2, slot * 2).toInt(16)
            if (mask and (1L shl (slot - 1)) != 0L) units in 1..40 else units == 0
        }
    }

    fun at(encoded: String, slot: Int): Long = encoded.substring((slot - 1) * 2, slot * 2).toLong(16) * VolumeRules.STEP_ML
}
