package fr.foulee.app

/**
 * Kind of each session, shared by the filters and the load comparison.
 * A kind chosen by hand always wins; otherwise: interval (detected from the laps), long (19 km or more),
 * strength (recorded on the watch), easy (any other run), other (any other sport).
 * Races are never guessed silently: likely ones are listed on the "À classer" page.
 */
object Categories {
    val all = listOf("competition", "interval", "long", "easy", "strength", "other")
    const val LONG_RUN_METERS = 19_000.0
    private val strengthTypes = listOf("strength_training", "indoor_cardio", "hiit")

    fun label(category: String) = when (category) {
        "competition" -> "Compétition"
        "interval" -> "Fractionné"
        "long" -> "Sortie longue"
        "easy" -> "Footing"
        "strength" -> "Musculation"
        else -> "Autre sport"
    }

    fun detected(a: ActivityRow): String = when {
        a.type in strengthTypes -> "strength"
        !a.type.contains("running") -> "other"
        a.intervalTitle != null -> "interval"
        // Eloi put the label on Strava himself: a long run there is a long run here, whatever the distance
        a.strava?.hasLabel("LongRun") == true -> "long"
        (a.distance ?: 0.0) >= LONG_RUN_METERS -> "long"
        else -> "easy"
    }

    fun of(a: ActivityRow): String = a.manualCategory?.takeIf { it in all } ?: detected(a)
}
