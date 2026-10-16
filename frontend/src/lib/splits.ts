import type { Sample } from "./api";

export interface Split {
    km: number;
    distance: number; // meters (the last split can be shorter than 1000)
    duration: number; // seconds
    averageHr: number | null;
    elevationGain: number;
}

/** Per-kilometer splits, interpolated from the chart samples (Garmin only gives watch laps). */
export function computeSplits(samples: Sample[]): Split[] {
    const pts = samples.filter((s): s is Sample & { d: number; t: number } => s.d !== null && s.t !== null);
    if (pts.length < 2) return [];

    const splits: Split[] = [];
    let startT = pts[0].t;
    let startD = pts[0].d;
    let nextKm = Math.floor(startD / 1000) + 1;
    let hrSum = 0;
    let hrCount = 0;
    let gain = 0;

    for (let i = 1; i < pts.length; i++) {
        const a = pts[i - 1];
        const b = pts[i];
        if (b.hr !== null) {
            hrSum += b.hr;
            hrCount++;
        }
        if (a.ele !== null && b.ele !== null && b.ele > a.ele) gain += b.ele - a.ele;

        while (b.d >= nextKm * 1000 && b.d > a.d) {
            const boundary = nextKm * 1000;
            const t = a.t + ((boundary - a.d) / (b.d - a.d)) * (b.t - a.t);
            splits.push({ km: nextKm, distance: boundary - startD, duration: t - startT, averageHr: hrCount ? Math.round(hrSum / hrCount) : null, elevationGain: gain });
            startT = t;
            startD = boundary;
            nextKm++;
            hrSum = 0;
            hrCount = 0;
            gain = 0;
        }
    }

    const last = pts[pts.length - 1];
    // Keep a final partial split only if it is meaningful (> 100 m)
    if (last.d - startD > 100) {
        splits.push({ km: nextKm, distance: last.d - startD, duration: last.t - startT, averageHr: hrCount ? Math.round(hrSum / hrCount) : null, elevationGain: gain });
    }
    return splits;
}
