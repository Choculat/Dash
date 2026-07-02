package sh.margot.dash.services.smspool

import kotlin.math.roundToInt

/** Formats a size in GB, dropping to whole MB below 1 GB (decimal MB, matching SMSPool's labels). */
fun formatData(gb: Double): String =
    if (gb < 1.0) "${(gb * 1000).roundToInt()} MB"
    else if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB"
    else "$gb GB"

/** Normalizes an API string like "43.13 MB" or "0.1 GB" to the same MB/GB rule. */
fun formatData(raw: String): String {
    val n = raw.trim().takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return raw.ifBlank { "—" }
    return formatData(if (raw.contains("GB", ignoreCase = true)) n else n / 1000.0)
}
