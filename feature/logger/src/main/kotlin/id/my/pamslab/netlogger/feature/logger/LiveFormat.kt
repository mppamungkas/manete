package id.my.pamslab.netlogger.feature.logger

import id.my.pamslab.netlogger.core.model.session.Scenario
import java.util.Locale

// Pure formatting for the Live dashboard (decision 004), unit-tested.

/** "h:mm:ss" from nanoseconds; negative counts as 0. */
internal fun formatElapsed(ns: Long): String {
    val s = (ns / 1_000_000_000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
}

internal fun Scenario.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
