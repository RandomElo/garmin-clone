import { DailyMetric } from "./db.js";
import { loadOnDay, onLoadInvalidate } from "./load.js";
import { addDays, dec, hours, mean, sd, shortDuration } from "./text.js";

/**
 * How ready the body was on a given day, from the night and the last days, always compared to
 * the athlete's own recent values (a "normal" HRV or resting HR is personal):
 * - sleep: duration and score of the night before, against the 30 previous nights. The literature on
 *   acute sleep loss shows a clear drop in high-intensity intermittent performance, larger for
 *   afternoon / evening sessions (Craven et al. 2022, Sports Med).
 * - HRV: last night's value, on a log scale, against the 60 previous nights (the HRV-guided training
 *   approach: Plews, Kiviniemi, Vesterinen; a value below the normal range advises an easier day).
 * - resting HR, morning Body Battery, stress: against the 30 previous days.
 * - training: the fatigue (7 days versus the month) at the end of the previous day.
 */

export type FactorKey = "sleep" | "sleepScore" | "hrv" | "restingHr" | "bodyBattery" | "stress" | "fatigue";
export type FactorStatus = "good" | "normal" | "low";

export interface Factor {
    key: FactorKey;
    label: string;
    value: number;
    display: string;
    baseline: number | null;
    baselineDisplay: string | null;
    /** Signed deviation from the athlete's usual values, positive = favorable */
    z: number;
    status: FactorStatus;
    /** "1 h 05 de moins que d'habitude" */
    note: string;
}

export interface Readiness {
    /** Day the values come from (the latest day with a night recorded) */
    date: string;
    score: number;
    label: string;
    factors: Factor[];
}

const WEIGHTS: Record<FactorKey, number> = { hrv: 0.27, sleep: 0.22, sleepScore: 0.08, restingHr: 0.15, bodyBattery: 0.08, stress: 0.05, fatigue: 0.15 };

// Promise cached so concurrent requests share one query
let cache: { at: number; days: Promise<Map<string, DailyMetric>> } | null = null;
onLoadInvalidate(() => (cache = null));

function days(): Promise<Map<string, DailyMetric>> {
    if (!cache || Date.now() - cache.at > 10 * 60_000) {
        cache = { at: Date.now(), days: DailyMetric.findAll({ order: [["date", "ASC"]] }).then((rows) => new Map(rows.map((r) => [r.date, r]))) };
    }
    return cache.days;
}

/** Values of the `n` days before `date` (excluded). */
function history(map: Map<string, DailyMetric>, date: string, n: number, pick: (d: DailyMetric) => number | null): number[] {
    const values: number[] = [];
    for (let i = 1; i <= n; i++) {
        const v = map.get(addDays(date, -i));
        const x = v ? pick(v) : null;
        if (x !== null && x !== undefined) values.push(x);
    }
    return values;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi);

function statusOf(z: number): FactorStatus {
    if (z <= -1) return "low";
    if (z >= 0.8) return "good";
    return "normal";
}

/** Deviation in the units people read: "8 ms de plus", "3 bpm de moins"… */
function diffNote(delta: number, unit: string, more: string, less: string, digits = 0): string {
    const rounded = Number(delta.toFixed(digits));
    if (rounded === 0) return "comme d'habitude";
    return `${dec(Math.abs(rounded), digits)}${unit ? ` ${unit}` : ""} ${rounded > 0 ? more : less} que d'habitude`;
}

interface Spec {
    key: Exclude<FactorKey, "fatigue">;
    label: string;
    window: number;
    /** Lowest standard deviation, so a very stable history does not turn tiny changes into alarms */
    minSd: number;
    /** Higher is better? */
    sign: 1 | -1;
    get: (d: DailyMetric) => number | null;
    /** Scale on which deviations are measured (log for HRV) */
    scale?: (v: number) => number;
    display: (v: number) => string;
    note: (v: number, base: number) => string;
}

const specs: Spec[] = [
    {
        key: "sleep",
        label: "Sommeil",
        window: 30,
        minSd: 25 * 60,
        sign: 1,
        get: (d) => d.sleepSeconds,
        display: hours,
        note: (v, base) => (Math.abs(v - base) < 10 * 60 ? "comme d'habitude" : `${shortDuration(v - base)} de ${v > base ? "plus" : "moins"} que d'habitude`),
    },
    {
        key: "sleepScore",
        label: "Score de sommeil",
        window: 30,
        minSd: 4,
        sign: 1,
        get: (d) => d.sleepScore,
        display: (v) => `${Math.round(v)}/100`,
        note: (v, base) => diffNote(v - base, "points", "de plus", "de moins"),
    },
    {
        key: "hrv",
        label: "VFC de la nuit",
        window: 60,
        minSd: 0.06,
        sign: 1,
        get: (d) => d.hrvNight,
        scale: Math.log,
        display: (v) => `${Math.round(v)} ms`,
        note: (v, base) => diffNote(v - base, "ms", "de plus", "de moins"),
    },
    {
        key: "restingHr",
        label: "FC au repos",
        window: 30,
        minSd: 1.5,
        sign: -1,
        get: (d) => d.restingHr,
        display: (v) => `${Math.round(v)} bpm`,
        note: (v, base) => diffNote(v - base, "bpm", "de plus", "de moins"),
    },
    {
        key: "bodyBattery",
        label: "Body Battery au réveil",
        window: 30,
        minSd: 6,
        sign: 1,
        get: (d) => d.bodyBatteryHigh,
        display: (v) => `${Math.round(v)}/100`,
        note: (v, base) => diffNote(v - base, "points", "de plus", "de moins"),
    },
    {
        key: "stress",
        label: "Stress de la journée",
        window: 30,
        minSd: 3,
        sign: -1,
        get: (d) => d.avgStress,
        display: (v) => `${Math.round(v)}/100`,
        note: (v, base) => diffNote(v - base, "points", "de plus", "de moins"),
    },
];

const MIN_HISTORY = 7;

/**
 * Factors for a day. `stressDay` is the day whose stress counts: the session day for an afternoon or
 * evening session, the day before otherwise (and for today, whose stress is not complete yet).
 */
export async function readinessOn(date: string, stressDay = addDays(date, -1)): Promise<Readiness | null> {
    const map = await days();
    const factors: Factor[] = [];

    for (const spec of specs) {
        const day = spec.key === "stress" ? stressDay : date;
        const row = map.get(day);
        const raw = row ? spec.get(row) : null;
        if (raw === null || raw === undefined) continue;
        const past = history(map, day, spec.window, spec.get);
        const scale = spec.scale ?? ((v: number) => v);
        let z = 0;
        let baseline: number | null = null;
        if (past.length >= MIN_HISTORY) {
            const scaled = past.map(scale);
            const m = mean(scaled);
            z = (spec.sign * (scale(raw) - m)) / Math.max(sd(scaled), spec.minSd);
            baseline = spec.scale === Math.log ? Math.exp(m) : m;
        }
        // Below 6 h the literature sees a performance cost whatever the habits
        if (spec.key === "sleep" && raw < 6 * 3600) z = Math.min(z, -1.2);
        factors.push({
            key: spec.key,
            label: spec.label,
            value: raw,
            display: spec.display(raw),
            baseline,
            baselineDisplay: baseline === null ? null : spec.display(baseline),
            z: Math.round(z * 100) / 100,
            status: statusOf(z),
            note: baseline === null ? "pas encore assez d'historique" : spec.note(raw, baseline),
        });
    }

    const load = await loadOnDay(addDays(date, -1));
    if (load?.fatigue !== null && load?.fatigue !== undefined) {
        // 3 to 7 is the balanced band: penalize above, a little bonus when fresh
        const z = load.fatigue > 7 ? -(load.fatigue - 7) / 1.2 : load.fatigue < 3 ? 0.5 : 0;
        factors.push({
            key: "fatigue",
            label: "Fatigue d'entraînement",
            value: load.fatigue,
            display: `${dec(load.fatigue)}/10`,
            baseline: null,
            baselineDisplay: "3 à 7",
            z: Math.round(z * 100) / 100,
            status: statusOf(z),
            note: load.fatigue > 7 ? "semaine plus chargée que ton mois" : load.fatigue < 3 ? "semaine légère, tu es frais" : "dans la zone équilibrée",
        });
    }

    if (!factors.some((f) => f.key === "sleep" || f.key === "hrv")) return null;

    const totalWeight = factors.reduce((t, f) => t + WEIGHTS[f.key], 0);
    const weighted = factors.reduce((t, f) => t + WEIGHTS[f.key] * clamp(f.z, -2.5, 2), 0) / totalWeight;
    const score = Math.round(clamp(68 + 18 * weighted, 0, 100));
    return { date, score, label: labelOf(score), factors };
}

export function labelOf(score: number): string {
    if (score >= 82) return "Au top";
    if (score >= 66) return "Prêt";
    if (score >= 52) return "Correct";
    if (score >= 38) return "Fatigué";
    return "À ménager";
}

/** Readiness of today, or of the latest day with a night recorded when the watch has not synced yet. */
export async function latestReadiness(): Promise<Readiness | null> {
    const map = await days();
    const dates = [...map.keys()].filter((d) => map.get(d)!.sleepSeconds !== null || map.get(d)!.hrvNight !== null).sort();
    const last = dates.at(-1);
    return last ? readinessOn(last) : null;
}

/** All daily rows, shared with the session analysis. */
export const dailyMetrics = days;
