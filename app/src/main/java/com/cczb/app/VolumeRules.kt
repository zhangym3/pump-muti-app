package com.cczb.app

/** Shared water-volume limits used by the UI and the command boundary. */
object VolumeRules {
    const val MIN_ML = 50L
    const val MAX_ML = 2000L
    const val STEP_ML = 50L

    fun isValid(value: Long): Boolean = value in MIN_ML..MAX_ML && value % STEP_ML == 0L

    fun clamp(value: Long): Long = value.coerceIn(MIN_ML, MAX_ML)

    /** Return the closest legal value, preferring the lower value on a tie. */
    fun snap(value: Long): Long {
        val clamped = clamp(value)
        val lower = clamped / STEP_ML * STEP_ML
        val upper = (lower + STEP_ML).coerceAtMost(MAX_ML)
        return if (clamped - lower <= upper - clamped) lower else upper
    }

    fun previous(value: Long?): Long {
        val current = clamp(value ?: MIN_ML)
        return if (isValid(current)) (current - STEP_ML).coerceAtLeast(MIN_ML)
        else ((current / STEP_ML) * STEP_ML).coerceAtLeast(MIN_ML)
    }

    fun next(value: Long?): Long {
        val current = clamp(value ?: MIN_ML)
        return if (isValid(current)) (current + STEP_ML).coerceAtMost(MAX_ML)
        else (((current + STEP_ML - 1) / STEP_ML) * STEP_ML).coerceIn(MIN_ML, MAX_ML)
    }
}
