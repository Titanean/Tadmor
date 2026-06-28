package com.tadmor.domain.model

/**
 * User-facing disposition for a body in the catalog.
 *
 * Three values; raw NASA disposition strings (TOI's `tfopwg_disp`,
 * KOI's `koi_disposition`) are normalised here. APC (ambiguous planet
 * candidate) is folded into [CANDIDATE] — too rare to warrant its own
 * tab/badge, and reads as "candidate, lower confidence" to a user.
 */
enum class Disposition {
    CONFIRMED,
    CANDIDATE,
    FALSE_POSITIVE;

    companion object {
        /**
         * Maps a raw NASA disposition string (TOI or KOI) to a domain value.
         * Returns null when the string is unrecognised — caller decides
         * whether to skip the row or default it.
         *
         * TOI `tfopwg_disp`: CP, KP, PC, APC, FP, FA
         * KOI `koi_disposition`: CONFIRMED, CANDIDATE, FALSE POSITIVE
         */
        fun fromRaw(raw: String?): Disposition? {
            val normalised = raw?.trim()?.uppercase() ?: return null
            return when (normalised) {
                "CP", "KP", "CONFIRMED" -> CONFIRMED
                "PC", "APC", "CANDIDATE" -> CANDIDATE
                "FP", "FA", "FALSE POSITIVE", "FALSE_POSITIVE", "REFUTED" -> FALSE_POSITIVE
                else -> null
            }
        }
    }
}
