package fr.foulee.app

class Split(val km: Int, val distance: Double, val duration: Double, val averageHr: Int?, val elevationGain: Double)

/** Per-kilometer splits, interpolated from the curve samples (Garmin only gives the watch laps). Port of the web app. */
object Splits {
    fun compute(samples: List<Sample>): List<Split> {
        val pts = samples.filter { it.d != null && it.t != null }
        if (pts.size < 2) return emptyList()
        val splits = mutableListOf<Split>()
        var startT = pts[0].t!!
        var startD = pts[0].d!!
        var nextKm = (startD / 1000).toInt() + 1
        var hrSum = 0.0
        var hrCount = 0
        var gain = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            b.hr?.let { hrSum += it; hrCount++ }
            if (a.ele != null && b.ele != null && b.ele > a.ele) gain += b.ele - a.ele
            while (b.d!! >= nextKm * 1000 && b.d > a.d!!) {
                val boundary = nextKm * 1000.0
                val t = a.t!! + (boundary - a.d) / (b.d - a.d) * (b.t!! - a.t)
                splits += Split(nextKm, boundary - startD, t - startT, if (hrCount > 0) Math.round(hrSum / hrCount).toInt() else null, gain)
                startT = t
                startD = boundary
                nextKm++
                hrSum = 0.0
                hrCount = 0
                gain = 0.0
            }
        }
        val last = pts.last()
        // Keep a final partial split only if it is meaningful (> 100 m)
        if (last.d!! - startD > 100) splits += Split(nextKm, last.d - startD, last.t!! - startT, if (hrCount > 0) Math.round(hrSum / hrCount).toInt() else null, gain)
        return splits
    }
}
