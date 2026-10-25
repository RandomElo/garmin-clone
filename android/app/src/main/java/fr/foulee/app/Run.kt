package fr.foulee.app

import java.util.Locale

/** A finished run, with the two form values read at the same time. */
data class Run(
    val id: Long,
    val distanceKm: Double,
    val paceSecPerKm: Double?,
    val form: Form,
) {
    val title: String get() = "Course terminée : ${format1(distanceKm)} km"

    val summary: String
        get() = listOf(
            "Allure " + (paceSecPerKm?.let { formatPace(it) } ?: "n/d"),
            "Forme " + (form.readiness?.let { "$it/100" + (form.readinessLabel?.let { l -> " ($l)" } ?: "") } ?: "n/d"),
            "Fatigue " + (form.fatigue?.let { "${format1(it)}/10" + (form.fatigueZone?.let { z -> " (${zoneLabel(z)})" } ?: "") } ?: "n/d"),
        ).joinToString(" · ")

    companion object {
        fun from(activity: ActivityRow, form: Form): Run {
            val meters = activity.distance ?: 0.0
            val speed = activity.averageSpeed ?: 0.0
            val duration = activity.duration ?: 0.0
            val pace = when {
                speed > 0 -> 1000 / speed
                meters > 0 && duration > 0 -> duration / meters * 1000
                else -> null
            }
            return Run(activity.id, meters / 1000, pace, form)
        }

        private fun format1(value: Double) = String.format(Locale.FRANCE, "%.1f", value)

        /** 272.4 s/km -> "4:32 /km" */
        fun formatPace(secPerKm: Double): String {
            val total = Math.round(secPerKm).toInt()
            return String.format(Locale.FRANCE, "%d:%02d /km", total / 60, total % 60)
        }

        private fun zoneLabel(zone: String) = when (zone) {
            "fresh" -> "reposé"
            "optimal" -> "équilibrée"
            "high" -> "élevée"
            "overload" -> "surcharge"
            else -> zone
        }
    }
}
