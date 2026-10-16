import { Op } from "sequelize";
import { Activity, ActivityDetail, type Lap, type Sample } from "./db.js";
import { analyzeIntervals, type IntervalAnalysis, type RepGroup } from "./intervals.js";
import { effortOf, loadOf, onLoadInvalidate } from "./load.js";
import { readinessOn, type Factor } from "./readiness.js";
import { addDays, capitalize, dec, hours, join, mean, median, ordinal, pace, pick, plural, sd, seedOf } from "./text.js";

/**
 * Automatic review of an interval session.
 *
 * 1. Performance: each rep is compared with the athlete's past reps of a similar duration (±35 %,
 *    last 12 months). Duration rather than distance, because the sustainable pace depends on how long
 *    the effort lasts: a 1000 m rep and a 3 min 30 rep are the same kind of effort. The session index
 *    is the duration-weighted pace gap, in %; heart rate is compared the same way.
 * 2. How it went: fade (last third of the reps versus the first), regularity, heart-rate recovery
 *    during the recoveries.
 * 3. Why: the context of the day (sleep, HRV, resting HR, Body Battery, stress, fatigue, rest since the
 *    last hard session, heat). Personal correlations are measured over all the interval sessions; when
 *    the history says nothing clear, effects from the literature are used, with conservative sizes:
 *    acute sleep loss (Craven et al. 2022, Sports Med: high-intensity intermittent work is among the
 *    most affected), HRV below the normal range (HRV-guided training studies), heat (~0.3-0.4 % per °C
 *    above the optimum, Vugts / El Helou race analyses).
 */

export interface RepVerdict {
    group: number;
    rep: number;
    speed: number;
    /** Usual speed for a rep of this duration, null without history */
    reference: number | null;
    /** Seconds per km versus the usual pace: negative = faster */
    deltaSecPerKm: number | null;
    hr: number | null;
}

export type VerdictKey = "excellent" | "good" | "standard" | "controlled" | "below" | "hard" | "first";

export interface Contribution {
    key: CtxKey;
    label: string;
    /** "5 h 40 (7 h 10 d'habitude)" */
    detail: string;
    /** Estimated effect on the pace, % (negative = slower) */
    effectPct: number;
    effectSecPerKm: number;
    source: "perso" | "études";
}

export interface Correlation {
    key: CtxKey;
    label: string;
    n: number;
    rho: number;
    p: number;
    strength: "nette" | "légère" | "aucune";
    /** Effect, % of pace, of the unfavorable half versus the favorable one (negative) */
    splitEffectPct: number;
    text: string;
}

export interface SessionAnalysis {
    verdict: VerdictKey;
    verdictLabel: string;
    /** Duration-weighted pace gap versus the usual reps, % (positive = faster) */
    paceIndex: number | null;
    paceDeltaSecPerKm: number | null;
    hrDelta: number | null;
    fadePct: number | null;
    hrRecovery: { avg: number; first: number; last: number } | null;
    reps: RepVerdict[];
    factors: Factor[];
    contributions: Contribution[];
    correlations: Correlation[];
    /** Number of interval sessions behind the personal correlations */
    sampleSize: number;
    paragraphs: string[];
    /** One-line version for the home page */
    summary: string;
}

type CtxKey = "sleep" | "sleepScore" | "hrv" | "restingHr" | "bodyBattery" | "stress" | "fatigue" | "rest" | "heat" | "hour";

interface RepRecord {
    sessionId: number;
    /** Format of the group ("1000 m", "30 s") */
    label: string;
    date: string;
    duration: number;
    speed: number;
    hr: number | null;
}

interface SessionRecord {
    id: number;
    date: string;
    startTimeLocal: string;
    analysis: IntervalAnalysis;
    reps: RepVerdict[];
    paceIndex: number | null;
    hrDelta: number | null;
    avgSpeed: number;
    context: Partial<Record<CtxKey, number>>;
    factors: Factor[];
}

const SIMILAR_DURATION = 1.35;
// Longer blocks are warm-ups or cool-downs taken for reps by the lap detection, not efforts to rate
const MAX_REP_SECONDS = 15 * 60;
const HISTORY_DAYS = 365;
// Recent sessions count more in the reference: weight halves every 60 days
const HALF_LIFE = 60;

// Promise cached so concurrent requests share one build
let cache: { at: number; sessions: Promise<SessionRecord[]> } | null = null;
onLoadInvalidate(() => (cache = null));

const speedOf = (l: Lap) => (l.distance ?? 0) / (l.duration ?? 1);

/** Reference for a rep: median of similar past reps, or a log-log pace / duration fit on all of them. */
/** Median where recent values count more (half-life of HALF_LIFE days). */
function weightedMedian(items: { value: number; date: string }[], date: string): number {
    const now = Date.parse(date);
    const weighted = items.map((i) => ({ value: i.value, w: Math.pow(0.5, (now - Date.parse(i.date)) / 86_400_000 / HALF_LIFE) })).sort((a, b) => a.value - b.value);
    const total = weighted.reduce((t, i) => t + i.w, 0);
    let acc = 0;
    for (const i of weighted) {
        acc += i.w;
        if (acc >= total / 2) return i.value;
    }
    return weighted[weighted.length - 1].value;
}

/**
 * Usual speed for a rep: first the same format (your "1000 m", your "30 s"), then reps of a similar
 * duration, then a pace / duration curve. Recent sessions weigh more, so the reference follows the
 * current fitness instead of last winter's. Laps far slower than the rest (a jog tagged as a rep) are left out.
 */
function referenceFor(label: string, duration: number, date: string, past: RepRecord[]): { speed: number; hr: number | null } | null {
    const from = addDays(date, -HISTORY_DAYS);
    const recent = past.filter((r) => r.date >= from);
    const usable = (reps: RepRecord[]) => {
        if (reps.length < 3 || new Set(reps.map((r) => r.sessionId)).size < 2) return null;
        const typical = median(reps.map((r) => r.speed));
        const kept = reps.filter((r) => r.speed >= typical * 0.8);
        const hrs = kept.filter((r) => r.hr !== null).map((r) => ({ value: r.hr!, date: r.date }));
        return {
            speed: weightedMedian(
                kept.map((r) => ({ value: r.speed, date: r.date })),
                date,
            ),
            hr: hrs.length >= 3 ? weightedMedian(hrs, date) : null,
        };
    };
    const sameFormat = usable(recent.filter((r) => r.label === label));
    if (sameFormat) return sameFormat;
    const similar = usable(recent.filter((r) => r.duration <= duration * SIMILAR_DURATION && r.duration >= duration / SIMILAR_DURATION));
    if (similar) return similar;
    if (recent.length < 12) return null;
    // Riegel-like curve: log(speed) = a + b log(duration)
    const xs = recent.map((r) => Math.log(r.duration));
    const ys = recent.map((r) => Math.log(r.speed));
    const mx = mean(xs);
    const my = mean(ys);
    const b = xs.reduce((t, x, i) => t + (x - mx) * (ys[i] - my), 0) / (xs.reduce((t, x) => t + (x - mx) ** 2, 0) || 1);
    return { speed: Math.exp(my + b * (Math.log(duration) - mx)), hr: null };
}

function rateSession(analysis: IntervalAnalysis, date: string, past: RepRecord[]) {
    const reps: RepVerdict[] = [];
    let weighted = 0;
    let covered = 0;
    let total = 0;
    const hrGaps: number[] = [];
    analysis.groups.forEach((g, gi) =>
        g.reps.forEach((r, ri) => {
            const duration = r.lap.duration!;
            const speed = speedOf(r.lap);
            const ref = g.avgDuration <= MAX_REP_SECONDS ? referenceFor(g.label, duration, date, past) : null;
            if (g.avgDuration <= MAX_REP_SECONDS) total += duration;
            if (ref) {
                weighted += (speed / ref.speed - 1) * duration;
                covered += duration;
                // Under 90 s the heart rate has no time to rise: its average says more about the recovery than the rep
                if (ref.hr !== null && r.lap.averageHr && duration >= 90) hrGaps.push(r.lap.averageHr - ref.hr);
            }
            reps.push({
                group: gi,
                rep: ri + 1,
                speed,
                reference: ref?.speed ?? null,
                deltaSecPerKm: ref ? Math.round(1000 / speed - 1000 / ref.speed) : null,
                hr: r.lap.averageHr,
            });
        }),
    );
    // Not enough comparable reps: no verdict rather than a misleading one
    const paceIndex = covered > 0 && covered >= total * 0.5 ? Math.round((weighted / covered) * 1000) / 10 : null;
    const hrDelta = hrGaps.length >= 2 ? Math.round(mean(hrGaps) * 10) / 10 : null;
    return { reps, paceIndex, hrDelta };
}

async function buildHistory(): Promise<SessionRecord[]> {
    const activities = await Activity.findAll({
        attributes: ["id", "type", "startTimeLocal", "intervalTitle", "trainingLoad", "trimp", "minTemperature"],
        order: [["startTimeLocal", "ASC"]],
    });
    // Days since the previous hard session (effort ≥ 6 / 10, intervals, tempo or long run)
    const hardDays: string[] = [];
    for (const a of activities) if ((effortOf(await loadOf(a)) ?? 0) >= 6) hardDays.push(a.startTimeLocal.slice(0, 10));

    const intervalRuns = activities.filter((a) => a.intervalTitle && a.type.includes("running"));
    const details = await ActivityDetail.findAll({ where: { activityId: { [Op.in]: intervalRuns.map((a) => a.id) } }, attributes: ["activityId", "laps"] });
    const lapsById = new Map(details.map((d) => [String(d.activityId), d.laps]));

    const past: RepRecord[] = [];
    const sessions: SessionRecord[] = [];
    for (const a of intervalRuns) {
        const laps = lapsById.get(String(a.id));
        const analysis = laps ? analyzeIntervals(laps) : null;
        if (!analysis) continue;
        const date = a.startTimeLocal.slice(0, 10);
        const hour = Number(a.startTimeLocal.slice(11, 13));
        const { reps, paceIndex, hrDelta } = rateSession(analysis, date, past);

        const readiness = await readinessOn(date, hour >= 14 ? date : addDays(date, -1));
        const factors = readiness?.factors ?? [];
        const value = (key: Factor["key"]) => factors.find((f) => f.key === key);
        const previousHard = hardDays.filter((d) => d < date).at(-1);
        const context: Partial<Record<CtxKey, number>> = {
            sleep: value("sleep") ? value("sleep")!.value / 3600 : undefined,
            sleepScore: value("sleepScore")?.value,
            hrv: value("hrv")?.z,
            restingHr: value("restingHr")?.baseline != null ? value("restingHr")!.value - value("restingHr")!.baseline! : undefined,
            bodyBattery: value("bodyBattery")?.value,
            stress: value("stress")?.value,
            fatigue: value("fatigue")?.value,
            rest: previousHard ? Math.round((Date.parse(date) - Date.parse(previousHard)) / 86_400_000) : undefined,
            heat: a.minTemperature ?? undefined,
            hour,
        };
        const efforts = analysis.groups.filter((g) => g.avgDuration <= MAX_REP_SECONDS);
        const work = (efforts.length ? efforts : analysis.groups).flatMap((g) => g.reps);
        sessions.push({
            id: a.id,
            date,
            startTimeLocal: a.startTimeLocal,
            analysis,
            reps,
            paceIndex,
            hrDelta,
            avgSpeed: work.reduce((t, r) => t + r.lap.distance!, 0) / work.reduce((t, r) => t + r.lap.duration!, 0),
            context,
            factors,
        });
        for (const g of analysis.groups) for (const r of g.reps) past.push({ sessionId: a.id, label: g.label, date, duration: r.lap.duration!, speed: speedOf(r.lap), hr: r.lap.averageHr });
    }
    return sessions;
}

async function history(): Promise<SessionRecord[]> {
    if (!cache || Date.now() - cache.at > 10 * 60_000) cache = { at: Date.now(), sessions: buildHistory() };
    return cache.sessions;
}

/* ---------- Statistics ---------- */

function ranks(values: number[]): number[] {
    const order = values.map((v, i) => [v, i] as const).sort((a, b) => a[0] - b[0]);
    const result = new Array<number>(values.length);
    for (let i = 0; i < order.length;) {
        let j = i;
        while (j + 1 < order.length && order[j + 1][0] === order[i][0]) j++;
        for (let k = i; k <= j; k++) result[order[k][1]] = (i + j) / 2;
        i = j + 1;
    }
    return result;
}

function pearson(xs: number[], ys: number[]): number {
    const mx = mean(xs);
    const my = mean(ys);
    let num = 0;
    let dx = 0;
    let dy = 0;
    for (let i = 0; i < xs.length; i++) {
        num += (xs[i] - mx) * (ys[i] - my);
        dx += (xs[i] - mx) ** 2;
        dy += (ys[i] - my) ** 2;
    }
    return dx && dy ? num / Math.sqrt(dx * dy) : 0;
}

/** Standard normal CDF (Abramowitz-Stegun erf approximation). */
function phi(x: number): number {
    const t = 1 / (1 + 0.3275911 * (Math.abs(x) / Math.SQRT2));
    const erf = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-(x * x) / 2);
    return x >= 0 ? (1 + erf) / 2 : (1 - erf) / 2;
}

/** Two-sided p-value of a Spearman rho, through the Fisher transform. */
const pValue = (rho: number, n: number) => (n > 3 ? 2 * (1 - phi(Math.abs(Math.atanh(Math.max(Math.min(rho, 0.999), -0.999))) * Math.sqrt(n - 3))) : 1);

const slopeOf = (xs: number[], ys: number[]) => {
    const mx = mean(xs);
    const my = mean(ys);
    const den = xs.reduce((t, x) => t + (x - mx) ** 2, 0);
    return den ? xs.reduce((t, x, i) => t + (x - mx) * (ys[i] - my), 0) / den : 0;
};

/* ---------- Context variables ---------- */

interface CtxSpec {
    key: CtxKey;
    label: string;
    /** Expected direction from physiology: +1 = more is better, -1 = more is worse, 0 = no prior */
    sign: 1 | -1 | 0;
    format: (v: number) => string;
    /** "tu dors moins de 7 h 05" / "tu dors plus de 7 h 05" */
    below: (s: number) => string;
    above: (s: number) => string;
    /** Effect from the literature, % of pace, for a value v when the athlete's typical value is t */
    prior: (v: number, t: number) => number;
}

const signed = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${dec(Math.abs(v))}`;

const ctxSpecs: CtxSpec[] = [
    {
        key: "sleep",
        label: "Sommeil",
        sign: 1,
        format: (v) => hours(v * 3600),
        below: (s) => `tu dors moins de ${hours(s * 3600)}`,
        above: (s) => `tu dors plus de ${hours(s * 3600)}`,
        // ~1 % per hour short, a bit more below 6 h; a long night helps a little
        prior: (v, t) => (v < t ? Math.max(-1 * (t - v) - (v < 6 ? 0.8 : 0), -4) : Math.min(0.4 * (v - t), 0.8)),
    },
    {
        key: "sleepScore",
        label: "Score de sommeil",
        sign: 1,
        format: (v) => `${Math.round(v)}/100`,
        below: (s) => `ton score de sommeil est sous ${Math.round(s)}`,
        above: (s) => `ton score de sommeil dépasse ${Math.round(s)}`,
        prior: (v, t) => (v < t - 10 ? -0.04 * (t - 10 - v) : 0),
    },
    {
        key: "hrv",
        label: "VFC de la nuit",
        sign: 1,
        format: (v) => `${signed(v)} écart-type`,
        below: () => "ta VFC de la nuit est dans le bas de ta fourchette",
        above: () => "ta VFC de la nuit est dans le haut de ta fourchette",
        prior: (v) => (v < -1 ? Math.max(0.9 * (v + 0.5), -3) : v > 1 ? 0.4 : 0),
    },
    {
        key: "restingHr",
        label: "FC au repos",
        sign: -1,
        format: (v) => `${signed(v)} bpm`,
        below: () => "ta FC au repos est à son niveau habituel",
        above: (s) => `ta FC au repos dépasse ta moyenne de ${dec(Math.max(s, 1), 0)} bpm ou plus`,
        prior: (v) => (v > 2 ? -0.3 * (v - 2) : 0),
    },
    {
        key: "bodyBattery",
        label: "Body Battery au réveil",
        sign: 1,
        format: (v) => `${Math.round(v)}/100`,
        below: (s) => `ta Body Battery au réveil est sous ${Math.round(s)}`,
        above: (s) => `ta Body Battery au réveil dépasse ${Math.round(s)}`,
        prior: (v, t) => (v < t - 15 ? -0.5 : 0),
    },
    {
        key: "stress",
        label: "Stress",
        sign: -1,
        format: (v) => `${Math.round(v)}/100`,
        below: (s) => `ton stress moyen reste sous ${Math.round(s)}`,
        above: (s) => `ton stress moyen dépasse ${Math.round(s)}`,
        prior: (v, t) => (v > t + 8 ? -0.4 : 0),
    },
    {
        key: "fatigue",
        label: "Fatigue d'entraînement",
        sign: -1,
        format: (v) => `${dec(v)}/10`,
        below: (s) => `ta fatigue est sous ${dec(s)}/10`,
        above: (s) => `ta fatigue dépasse ${dec(s)}/10`,
        prior: (v) => (v > 7 ? -0.6 * (v - 7) : 0),
    },
    {
        key: "rest",
        label: "Repos depuis la dernière séance dure",
        sign: 1,
        format: (v) => plural(v, "jour"),
        below: (s) => `ta dernière séance dure date de ${plural(Math.round(s), "jour")} ou moins`,
        above: (s) => `ta dernière séance dure date de plus de ${plural(Math.round(s), "jour")}`,
        prior: (v) => (v <= 1 ? -1.5 : 0),
    },
    {
        key: "heat",
        label: "Température (montre)",
        sign: -1,
        format: (v) => `${Math.round(v)} °C`,
        below: (s) => `la montre affiche moins de ${Math.round(s)} °C`,
        above: (s) => `la montre affiche plus de ${Math.round(s)} °C`,
        // The watch reads a few degrees above the air (body heat): only the gap to the usual value counts
        prior: (v, t) => (v > Math.max(t, 15) + 2 ? -0.3 * (v - Math.max(t, 15) - 2) : 0),
    },
    {
        key: "hour",
        label: "Heure de la séance",
        sign: 0,
        format: (v) => `${Math.round(v)} h`,
        below: (s) => `tu cours avant ${Math.round(s)} h`,
        above: (s) => `tu cours après ${Math.round(s)} h`,
        prior: () => 0,
    },
];

const MIN_SESSIONS = 10;

function correlate(spec: CtxSpec, sessions: SessionRecord[], refSpeed: number): Correlation | null {
    const pairs = sessions.filter((s) => s.paceIndex !== null && s.context[spec.key] !== undefined).map((s) => [s.context[spec.key]!, s.paceIndex!] as const);
    if (pairs.length < MIN_SESSIONS) return null;
    const xs = pairs.map((p) => p[0]);
    const ys = pairs.map((p) => p[1]);
    const rho = pearson(ranks(xs), ranks(ys));
    const p = pValue(rho, pairs.length);
    const split = median(xs);
    const low = pairs.filter((q) => q[0] <= split).map((q) => q[1]);
    const high = pairs.filter((q) => q[0] > split).map((q) => q[1]);
    if (!low.length || !high.length) return null;
    const diff = mean(high) - mean(low);
    // Physiology says which way it should go: a relation in the other direction is most likely chance
    const plausible = spec.sign === 0 || Math.sign(diff) === spec.sign || p < 0.05;
    const strength = plausible && Math.abs(rho) >= 0.3 && p < 0.1 ? "nette" : plausible && Math.abs(rho) >= 0.18 && p < 0.3 ? "légère" : "aucune";
    const worse = diff > 0 ? spec.below(split) : spec.above(split);
    const pct = Math.abs(diff);
    const secs = Math.round((1000 / refSpeed) * (pct / 100));
    const text = strength === "aucune" ? `${spec.label} : pas de lien visible avec tes allures sur ${plural(pairs.length, "séance")}.` : `Quand ${worse}, tes répétitions sont en moyenne ${dec(pct)} % plus lentes${secs ? ` (environ ${secs} s/km)` : ""}, sur ${plural(pairs.length, "séance")}.`;
    return { key: spec.key, label: spec.label, n: pairs.length, rho: Math.round(rho * 100) / 100, p: Math.round(p * 1000) / 1000, strength, splitEffectPct: -Math.round(pct * 10) / 10, text };
}

/* ---------- How the session went ---------- */

function mainGroup(analysis: IntervalAnalysis): RepGroup {
    const efforts = analysis.groups.filter((g) => g.avgDuration <= MAX_REP_SECONDS);
    return (efforts.length ? efforts : analysis.groups).reduce((best, g) => (g.reps.length * g.avgDuration > best.reps.length * best.avgDuration ? g : best));
}

/** Pace of the last third of the reps versus the first third, % (negative = slowed down). */
function fadeOf(g: RepGroup): { pct: number; worst: number; best: number; collapseAt: number | null } | null {
    if (g.reps.length < 3) return null;
    const speeds = g.reps.map((r) => speedOf(r.lap));
    const third = Math.max(1, Math.floor(speeds.length / 3));
    const pct = (mean(speeds.slice(-third)) / mean(speeds.slice(0, third)) - 1) * 100;
    // First rep from which every rep stays clearly slower than the start
    const start = mean(speeds.slice(0, third));
    let collapseAt: number | null = null;
    for (let i = third; i < speeds.length; i++) {
        if (speeds.slice(i).every((s) => s < start * 0.98)) {
            collapseAt = i + 1;
            break;
        }
    }
    return { pct: Math.round(pct * 10) / 10, worst: speeds.indexOf(Math.min(...speeds)) + 1, best: speeds.indexOf(Math.max(...speeds)) + 1, collapseAt };
}

/** Heart-rate drop during the first minute of each recovery (bpm). */
function hrRecovery(analysis: IntervalAnalysis, allLaps: Lap[], samples: Sample[]) {
    const points = samples.filter((s) => s.t !== null && s.hr);
    if (points.length < 20) return null;
    const starts: number[] = [];
    allLaps.reduce((t, l) => {
        starts.push(t);
        return t + (l.duration ?? 0);
    }, 0);
    const hrAt = (t: number) => points.reduce((best, p) => (Math.abs(p.t! - t) < Math.abs(best.t! - t) ? p : best)).hr!;
    const drops: number[] = [];
    analysis.laps.forEach((info, i) => {
        const next = analysis.laps[i + 1];
        if (info.role !== "work" || next?.role !== "recovery" || (next.lap.duration ?? 0) < 50) return;
        const end = starts[allLaps.indexOf(next.lap)];
        if (end === undefined) return;
        const peak = Math.max(...points.filter((p) => p.t! >= end - 15 && p.t! <= end).map((p) => p.hr!), 0);
        const after = hrAt(end + Math.min(60, next.lap.duration! - 2));
        if (peak > 0 && peak - after > 0) drops.push(peak - after);
    });
    if (drops.length < 3) return null;
    const half = Math.floor(drops.length / 2);
    return { avg: Math.round(mean(drops)), first: Math.round(mean(drops.slice(0, half))), last: Math.round(mean(drops.slice(-half))) };
}

/* ---------- Verdict and text ---------- */

const verdictLabels: Record<VerdictKey, string> = {
    excellent: "Excellente séance",
    good: "Séance réussie",
    standard: "Dans tes standards",
    below: "Un ton en dessous",
    hard: "Séance difficile",
    first: "Nouvelle référence",
    controlled: "Séance contrôlée",
};

const verdictOrder: VerdictKey[] = ["hard", "below", "standard", "good", "excellent"];

/** Pace index minus a small cost per bpm above the usual (capped: HR also depends on heat, recoveries). */
const adjustedIndex = (paceIndex: number, hrDelta: number | null) => paceIndex - 0.12 * Math.max(Math.min(hrDelta ?? 0, 8), -8);

/**
 * Levels are relative to the athlete's own spread (robust SD of all session indices): a session one
 * spread below the median is a real off day for this athlete, whatever the absolute %.
 */
function verdictOf(paceIndex: number | null, hrDelta: number | null, fadePct: number | null, center: number, spread: number): VerdictKey {
    if (paceIndex === null) return "first";
    const z = (adjustedIndex(paceIndex, hrDelta) - center) / spread;
    // Slower, but with a clearly lower heart rate: an easier session by choice, not a failure
    if (z < -0.5 && hrDelta !== null && hrDelta <= -4) return "controlled";
    let level = z >= 1.1 ? 4 : z >= 0.4 ? 3 : z > -0.6 ? 2 : z > -1.3 ? 1 : 0;
    // A series that falls apart at the end is not a top session, whatever the average
    if (fadePct !== null && fadePct <= -4 && level > 2) level -= 1;
    return verdictOrder[level];
}

const groupName = (g: RepGroup) => `${g.reps.length > 1 ? `${g.reps.length} × ` : ""}${g.label.replaceAll(" ", " ")}`;

function describePerformance(seed: number, verdict: VerdictKey, analysis: IntervalAnalysis, session: SessionRecord): string {
    const g = mainGroup(analysis);
    const name = groupName(g);
    const others = analysis.groups.filter((x) => x !== g);
    const gap = session.paceIndex === null ? 0 : Math.round((1000 / session.avgSpeed) * (Math.abs(session.paceIndex) / 100));
    const at = `à ${pace(g.avgSpeed)} de moyenne`;
    const hr = session.hrDelta;
    const hrClause = hr === null ? "" : Math.abs(hr) < 2 ? pick(seed + 1, [", avec une FC comparable", ", pour une FC habituelle", ", cardio au même niveau que d'habitude"]) : hr < 0 ? pick(seed + 1, [`, avec ${dec(-hr, 0)} bpm de moins que d'habitude`, ` et une FC plus basse de ${dec(-hr, 0)} bpm`]) : pick(seed + 1, [`, mais avec ${dec(hr, 0)} bpm de plus que d'habitude`, `, au prix d'une FC plus haute de ${dec(hr, 0)} bpm`]);
    const extra = others.length ? ` (plus ${join(others.map(groupName))})` : "";

    if (verdict === "first") {
        return `${capitalize(name)}${extra} ${at}. Pas encore assez de répétitions de cette durée dans ton historique pour comparer : cette séance servira de référence.`;
    }
    const faster = session.paceIndex! >= 0;
    const vs = gap === 0 ? "exactement à ton allure habituelle sur ce type de répétition" : `${gap} s/km ${faster ? "plus vite" : "plus lent"} que d'habitude sur ce format`;
    switch (verdict) {
        case "excellent":
            return pick(seed, [`Très grosse séance. Tes ${name}${extra} sont passés ${at}, ${vs}${hrClause}.`, `${capitalize(name)}${extra} ${at} : c'est ${vs}${hrClause}.${session.paceIndex! >= 6 ? " Une de tes meilleures sur ce format." : ""}`]);
        case "good":
            return pick(seed, [`Bonne séance : ${name}${extra} ${at}, ${vs}${hrClause}.`, `Tes ${name}${extra} sont passés ${at}, soit ${vs}${hrClause}. Séance réussie.`]);
        case "standard":
            return pick(seed, [`${capitalize(name)}${extra} ${at}, ${vs}${hrClause}. Une séance dans tes standards.`, `Séance conforme à ce que tu fais d'habitude : ${name}${extra} ${at}, ${vs}${hrClause}.`]);
        case "below":
            return pick(seed, [`Séance un peu en dessous : ${name}${extra} ${at}, ${vs}${hrClause}.`, `Tes ${name}${extra} sont passés ${at}, ${vs}${hrClause}. Pas une mauvaise séance, mais pas ton meilleur jour.`]);
        case "controlled":
            return `${capitalize(name)}${extra} ${at}, ${vs}, mais ta FC aussi était plus basse (${dec(-(session.hrDelta ?? 0), 0)} bpm de moins). Plutôt une séance gérée qu'un jour sans : l'intensité était simplement plus basse.`;
        case "hard":
            return pick(seed, [`Séance compliquée : ${name}${extra} ${at}, ${vs}${hrClause}.`, `Jour sans sur ces ${name}${extra} : ${at}, ${vs}${hrClause}.`]);
    }
}

function describeCourse(seed: number, analysis: IntervalAnalysis, recovery: SessionAnalysis["hrRecovery"]): string | null {
    const g = mainGroup(analysis);
    const fade = fadeOf(g);
    const parts: string[] = [];
    if (fade) {
        const speeds = g.reps.map((r) => speedOf(r.lap));
        const spread = Math.round(1000 / Math.min(...speeds) - 1000 / Math.max(...speeds));
        if (fade.pct <= -3) {
            parts.push(fade.collapseAt ? pick(seed + 2, [`Ça s'est gâté à partir de la ${ordinal(fade.collapseAt)} répétition : la fin de série est ${dec(-fade.pct)} % plus lente que le début.`, `Le début était bon, mais à partir de la ${ordinal(fade.collapseAt)} tu n'as plus retrouvé ton allure (${dec(-fade.pct)} % plus lent sur la fin).`]) : `Tu as perdu ${dec(-fade.pct)} % d'allure entre le début et la fin de la série.`);
        } else if (fade.pct >= 1.5) {
            parts.push(pick(seed + 2, [`Tu as fini plus vite que tu n'as commencé (${dec(fade.pct)} % plus rapide sur le dernier tiers), signe qu'il restait de la marge.`, `Belle gestion : le dernier tiers de la série est ${dec(fade.pct)} % plus rapide que le premier.`]));
        } else if (spread <= 4) {
            parts.push(pick(seed + 2, [`Régularité de métronome : ${spread} s/km seulement entre la plus rapide et la plus lente.`, `Série très régulière, ${spread} s/km d'écart en tout.`]));
        }
    }
    if (recovery) {
        const trend = recovery.last - recovery.first;
        // Only worth a sentence when it degrades: the average is already shown as a figure
        if (trend <= -6) parts.push(`Pendant les récups, ta FC redescendait de ${recovery.first} bpm en une minute au début, puis seulement de ${recovery.last} bpm sur la fin : la fatigue s'installait.`);
    }
    return parts.length ? parts.join(" ") : null;
}

const factorDetail = (key: CtxKey, value: number, typical: number, factors: Factor[]): string => {
    const f = factors.find((x) => x.key === key);
    if (f) return f.baselineDisplay ? `${f.display} (${f.note})` : f.display;
    const spec = ctxSpecs.find((s) => s.key === key)!;
    return `${spec.format(value)} (d'habitude ${spec.format(typical)})`;
};

/** Estimated effect of each unusual context value on this session's pace. */
function contributions(session: SessionRecord, sessions: SessionRecord[], correlations: Correlation[]): Contribution[] {
    const result: Contribution[] = [];
    for (const spec of ctxSpecs) {
        const value = session.context[spec.key];
        if (value === undefined || spec.key === "hour") continue;
        // A value close to the usual one explains nothing, whatever the slope
        const factor = session.factors.find((f) => f.key === spec.key);
        if (factor && factor.baseline !== null && Math.abs(factor.z) < 0.5) continue;
        const values = sessions.map((s) => s.context[spec.key]).filter((v): v is number => v !== undefined);
        const typical = values.length >= 5 ? median(values) : spec.key === "hrv" || spec.key === "restingHr" ? 0 : value;
        const corr = correlations.find((c) => c.key === spec.key);
        let effect = spec.prior(value, typical);
        let source: Contribution["source"] = "études";
        if (corr && corr.strength !== "aucune") {
            // Personal slope, % of pace per unit, shrunk toward zero when the link is weak
            const pairs = sessions.filter((s) => s.paceIndex !== null && s.context[spec.key] !== undefined);
            const slope = slopeOf(
                pairs.map((s) => s.context[spec.key]!),
                pairs.map((s) => s.paceIndex!),
            );
            const spread = sd(values) || 1;
            const deviation = Math.max(Math.min(value - typical, 2.5 * spread), -2.5 * spread);
            effect = slope * deviation * (corr.strength === "nette" ? 1 : 0.6);
            source = "perso";
        }
        if (Math.abs(effect) < 0.4) continue;
        effect = Math.round(effect * 10) / 10;
        result.push({
            key: spec.key,
            label: spec.label,
            detail: factorDetail(spec.key, value, typical, session.factors),
            effectPct: effect,
            effectSecPerKm: Math.round((1000 / session.avgSpeed) * (-effect / 100)),
            source,
        });
    }
    return result.sort((a, b) => Math.abs(b.effectPct) - Math.abs(a.effectPct));
}

/** "une nuit de 5 h 40, 1 h 30 de moins que d'habitude" */
function phraseOf(c: Contribution, session: SessionRecord): string {
    const f = session.factors.find((x) => x.key === c.key);
    const v = session.context[c.key]!;
    switch (c.key) {
        case "sleep":
            return f ? `une nuit de ${f.display}, ${f.note}` : "une nuit courte";
        case "sleepScore":
            return f ? `un score de sommeil de ${f.display}` : "un sommeil de mauvaise qualité";
        case "hrv":
            return f ? `une VFC à ${f.display}, ${f.note}${f.z <= -1 ? " (sous ta fourchette normale)" : f.z >= 1 ? " (dans le haut de ta fourchette)" : ""}` : "une VFC basse";
        case "restingHr":
            return f ? `une FC au repos à ${f.display}, ${f.note}` : "une FC au repos élevée";
        case "bodyBattery":
            return f ? `une Body Battery à ${f.display} au réveil` : "une Body Battery basse";
        case "stress":
            if (!f) return c.effectPct < 0 ? "une journée stressante" : "une journée calme";
            return c.effectPct < 0 ? `une journée stressante (${f.display}, ${f.note})` : `une journée calme (stress à ${f.display})`;
        case "fatigue":
            return `une fatigue d'entraînement à ${dec(v)}/10`;
        case "rest":
            return v <= 1 ? (v === 0 ? "une autre séance dure le même jour" : "une séance dure la veille") : `${plural(v, "jour")} de récup depuis la dernière séance dure`;
        case "heat":
            return `de la chaleur (${Math.round(v)} °C à la montre)`;
        default:
            return c.label.toLowerCase();
    }
}

function describeWhy(seed: number, verdict: VerdictKey, session: SessionRecord, contribs: Contribution[], correlations: Correlation[]): string {
    const negatives = contribs.filter((c) => c.effectPct < 0);
    const positives = contribs.filter((c) => c.effectPct > 0);
    const personal = (key: CtxKey) => correlations.find((c) => c.key === key && c.strength !== "aucune");
    const poor = verdict === "below" || verdict === "hard";
    const great = verdict === "excellent" || verdict === "good";
    const sentences: string[] = [];

    const quantified = (list: Contribution[]) => {
        const secs = list.reduce((t, c) => t + Math.abs(c.effectSecPerKm), 0);
        if (secs < 1) return "";
        return list.length > 1 ? ` ${pick(seed + 5, ["À eux deux, ils pèsent", "Ensemble, ça représente"])} environ ${secs} s/km.` : ` Ça pèse à peu près ${secs} s/km.`;
    };

    if (poor && negatives.length) {
        const top = negatives.slice(0, 2);
        sentences.push(pick(seed + 4, [`Le contexte explique une bonne partie de l'écart : ${join(top.map((c) => phraseOf(c, session)))}.`, `Ce n'est pas un hasard : ${join(top.map((c) => phraseOf(c, session)))}.`, `Il y a une raison : ${join(top.map((c) => phraseOf(c, session)))}.`]) + quantified(top));
        const link = top.map((c) => personal(c.key)).find(Boolean);
        if (link) sentences.push(`Ce n'est pas qu'une impression : ${link.text.charAt(0).toLowerCase()}${link.text.slice(1)}`);
        else if (top.some((c) => c.key === "sleep")) sentences.push("Les études sur le manque de sommeil vont dans le même sens : ce sont justement les efforts intenses et répétés qui en souffrent le plus, surtout en fin de journée.");
    } else if (poor) {
        sentences.push(pick(seed + 4, ["Pourtant rien d'anormal côté récupération : sommeil, VFC et FC au repos étaient dans ta norme. Ça arrive, une séance moyenne isolée ne veut rien dire.", "Côté récupération, rien ne l'explique : nuit, VFC et FC au repos étaient normales. Si ça se répète, c'est plutôt la charge des dernières semaines qu'il faudra regarder."]));
    } else if (great && negatives.length) {
        sentences.push(pick(seed + 4, [`Et ça malgré ${join(negatives.slice(0, 2).map((c) => phraseOf(c, session)))}.`, `D'autant plus solide que le contexte n'aidait pas : ${join(negatives.slice(0, 2).map((c) => phraseOf(c, session)))}.`]));
    } else if (great && positives.length) {
        sentences.push(pick(seed + 4, [`Le terrain était favorable : ${join(positives.slice(0, 2).map((c) => phraseOf(c, session)))}.`, `Tu arrivais bien récupéré, avec ${join(positives.slice(0, 2).map((c) => phraseOf(c, session)))}.`]));
        const link = positives.map((c) => personal(c.key)).find(Boolean);
        if (link) sentences.push(`Ça colle avec ton historique : ${link.text.charAt(0).toLowerCase()}${link.text.slice(1)}`);
    } else if (negatives.length) {
        sentences.push(`À noter : ${join(negatives.slice(0, 2).map((c) => phraseOf(c, session)))}, sans que ça t'empêche de tenir tes allures.`);
    }
    return sentences.join(" ");
}

function advice(seed: number, verdict: VerdictKey, contribs: Contribution[], fade: ReturnType<typeof fadeOf>): string | null {
    const top = contribs.find((c) => c.effectPct < 0);
    if ((verdict === "below" || verdict === "hard") && top) {
        switch (top.key) {
            case "sleep":
            case "sleepScore":
                return pick(seed + 6, ["Après une nuit aussi courte, mieux vaut garder la séance mais enlever une ou deux répétitions, ou la décaler d'un jour.", "Une piste simple : les veilles de fractionné, viser au moins ta durée de sommeil habituelle."]);
            case "hrv":
            case "restingHr":
                return "Quand la VFC sort de ta fourchette basse ou que la FC au repos grimpe, une séance plus courte ou un footing passe souvent mieux que le plan prévu.";
            case "rest":
                return "Laisse au moins un jour facile entre deux séances dures : c'est souvent le plus rentable.";
            case "fatigue":
                return "La charge des derniers jours était élevée : une semaine un peu plus légère te rendra tes allures.";
            case "heat":
                return "Par forte chaleur, raisonne à l'effort (FC) plutôt qu'à l'allure, ou cours plus tôt.";
            default:
                return null;
        }
    }
    if (fade && fade.pct <= -4 && verdict !== "hard") return "Pour la prochaine, pars 2 ou 3 s/km plus prudemment sur les premières : la série entière sera plus rapide.";
    return null;
}

/** Full analysis of an interval session, or null for a steady run. */
export async function analyzeSession(activityId: number, samples: Sample[], laps: Lap[]): Promise<SessionAnalysis | null> {
    const sessions = await history();
    const session = sessions.find((s) => String(s.id) === String(activityId));
    if (!session) return null;
    const others = sessions.filter((s) => s !== session);
    const refSpeed = median(sessions.map((s) => s.avgSpeed));
    const correlations = ctxSpecs
        .map((spec) => correlate(spec, others, refSpeed))
        .filter((c): c is Correlation => c !== null)
        .sort((a, b) => Math.abs(b.rho) - Math.abs(a.rho));

    const seed = seedOf(activityId);
    const contribs = contributions(session, others, correlations);
    const fade = fadeOf(mainGroup(session.analysis));
    const indices = sessions.filter((s) => s.paceIndex !== null).map((s) => adjustedIndex(s.paceIndex!, s.hrDelta));
    const center = indices.length >= 8 ? median(indices) : 0;
    const spread = indices.length >= 8 ? Math.max(1.4826 * median(indices.map((v) => Math.abs(v - center))), 1.5) : 3;
    const verdict = verdictOf(session.paceIndex, session.hrDelta, fade?.pct ?? null, center, spread);
    // Re-detected on the laps passed in: the lap start times are looked up by object identity
    const ownAnalysis = analyzeIntervals(laps);
    const recovery = ownAnalysis ? hrRecovery(ownAnalysis, laps, samples) : null;

    const paragraphs = [describePerformance(seed, verdict, session.analysis, session), describeCourse(seed, session.analysis, recovery), describeWhy(seed, verdict, session, contribs, correlations), advice(seed, verdict, contribs, fade)].filter((p): p is string => Boolean(p));

    return {
        verdict,
        verdictLabel: verdictLabels[verdict],
        paceIndex: session.paceIndex,
        paceDeltaSecPerKm: session.paceIndex === null ? null : Math.round((1000 / session.avgSpeed) * (-session.paceIndex / 100)),
        hrDelta: session.hrDelta,
        fadePct: fade?.pct ?? null,
        hrRecovery: recovery,
        reps: session.reps,
        factors: session.factors,
        contributions: contribs,
        correlations,
        sampleSize: others.filter((s) => s.paceIndex !== null).length,
        paragraphs,
        summary: paragraphs[0],
    };
}

/** Latest interval session, analyzed, for the home page. */
export async function latestSessionAnalysis(): Promise<{ id: number; startTimeLocal: string; title: string; analysis: SessionAnalysis } | null> {
    const last = (await history()).at(-1);
    if (!last) return null;
    const detail = await ActivityDetail.findByPk(last.id);
    const analysis = detail ? await analyzeSession(last.id, detail.samples, detail.laps) : null;
    return analysis ? { id: last.id, startTimeLocal: last.startTimeLocal, title: last.analysis.title, analysis } : null;
}
