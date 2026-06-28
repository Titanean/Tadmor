package com.tadmor.domain.classification

/**
 * Extracts the luminosity class from a spectral type string.
 *
 * Handles formats like "G2V", "K1 III", "F5IV-V", "M3.5Ve", "A0Ia", "B2II".
 * Returns null if no luminosity class can be determined (e.g. white dwarfs, bare letters).
 *
 * Standard luminosity classes: I, II, III, IV, V
 * (Ia/Ib/Iab are collapsed to "I" for filtering purposes.)
 */
fun extractLuminosityClass(spectralType: String?): String? {
    val sp = spectralType?.trim() ?: return null
    val upper = sp.uppercase()

    // White dwarfs have no luminosity class
    if (upper.startsWith("D") && upper.length >= 2 && upper[1] in "ABCOZQ") return null

    // Skip past the spectral class letter and optional numeric subtype (e.g. "G2", "K1.5", "M3")
    val first = upper.firstOrNull() ?: return null
    if (first !in "OBAFGKMLT") return null

    var i = 1
    // Skip digits and dots (subtype like "2", "1.5")
    while (i < upper.length && (upper[i].isDigit() || upper[i] == '.')) i++
    // Skip optional space
    while (i < upper.length && upper[i] == ' ') i++

    if (i >= upper.length) return null

    val rest = upper.substring(i)

    // Match luminosity class (order matters: check longer patterns first)
    return when {
        rest.startsWith("IV") -> "IV"
        rest.startsWith("III") -> "III"
        rest.startsWith("II") -> "II"
        rest.startsWith("V") -> "V"
        rest.startsWith("I") -> "I"  // I, Ia, Ib, Iab
        else -> null
    }
}
