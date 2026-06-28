package com.tadmor.domain.model

/**
 * Converts a NASA TAP API limit flag to a display prefix.
 * 1 = upper limit → "<", -1 = lower limit → ">", 0/null = measured → "".
 */
fun limitPrefix(flag: Int?): String = when (flag) {
    1 -> "<"
    -1 -> ">"
    else -> ""
}

/** Whether this limit flag indicates a non-measured (upper or lower limit) value. */
fun isLimitValue(flag: Int?): Boolean = flag != null && flag != 0
