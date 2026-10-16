import { Op } from "sequelize";
import { Activity, ActivityDetail, DailyMetric, getSetting, setSetting, type Sample } from "./db.js";
import { toDateKey } from "./garmin.js";

/**
 * Training load on simple scales:
 * - effort of a session, 0-10: Garmin's training load (EPOC based, it sees intervals well) on a saturating
 *   curve; without it (older watches), a heart-rate TRIMP converted to the same unit
 * - load over 7 days and over 4 weeks, 0-20: sum of the session loads, relative to the heaviest weeks / months
 *   of the history (20 = 99th percentile, so one exceptional week does not squash everything else)
 * - fatigue, 0-10: the last 7 days versus the average week of the last 4 (acute / chronic ratio)
 */

// Load giving an effort of ~6.3 (1 - 1/e): a solid interval session
const EFFORT_SCALE = 200;
const WEEK = 7;
const MONTH = 28;

export interface HrRefs {
    rest: number;
    max: number;
}

/** Resting HR: recent average. Max HR: 99th percentile of activity maxima over two years (ignores sensor spikes). */
export async function hrRefs(): Promise<HrRefs> {
    const envMax = Number(process.env.HR_MAX);
    const envRest = Number(process.env.HR_REST);
    const since = new Date();
    since.setDate(since.getDate() - 30);
    const rests = (await DailyMetric.findAll({ where: { date: { [Op.gte]: toDateKey(since) }, restingHr: { [Op.ne]: null } }, attributes: ["restingHr"] })).map((d) => d.restingHr!);
    since.setDate(since.getDate() - 700);
    const maxes = (
        await Activity.findAll({
            where: { maxHr: { [Op.ne]: null }, startTimeLocal: { [Op.gte]: toDateKey(since) } },
            attributes: ["maxHr"],
            order: [["maxHr", "DESC"]],
        })
    ).map((a) => a.maxHr!);
    return {
        rest: envRest || (rests.length ? Math.round(rests.reduce((a, b) => a + b, 0) / rests.length) : 50),
        max: envMax || maxes[Math.floor(maxes.length * 0.01)] || 190,
    };
}

/** Banister TRIMP from the heart-rate curve (or the average HR when there is no curve). */
export function computeTrimp(samples: Sample[], averageHr: number | null, duration: number | null, refs: HrRefs): number | null {
    const weight = (hr: number, minutes: number) => {
        const x = Math.min(Math.max((hr - refs.rest) / (refs.max - refs.rest), 0), 1);
        return minutes * x * 0.64 * Math.exp(1.92 * x);
    };
    const points = samples.filter((p) => p.t !== null && p.hr);
    if (points.length > 10) {
        let total = 0;
        for (let i = 1; i < points.length; i++) {
            // Cap gaps so a pause with the watch running does not count as effort
            const dt = Math.min(points[i].t! - points[i - 1].t!, 60);
            if (dt > 0) total += weight(points[i].hr!, dt / 60);
        }
        return total;
    }
    return averageHr && duration ? weight(averageHr, duration / 60) : null;
}

// Promise cached: loadOf() runs for every activity at once, they must share a single query
let ratioCache: { value: Promise<number>; at: number } | null = null;

/** Garmin load per TRIMP point, measured on the sessions that have both. */
function trimpToLoad(): Promise<number> {
    if (ratioCache && Date.now() - ratioCache.at < 10 * 60_000) return ratioCache.value;
    const value = Activity.findAll({
        where: { trainingLoad: { [Op.gt]: 0 }, trimp: { [Op.gt]: 10 } },
        attributes: ["trainingLoad", "trimp"],
    }).then((both) => {
        const ratios = both.map((a) => a.trainingLoad! / a.trimp!).sort((a, b) => a - b);
        return ratios.length ? ratios[Math.floor(ratios.length / 2)] : 1.3;
    });
    ratioCache = { value, at: Date.now() };
    return value;
}

const invalidators: (() => void)[] = [];

/** Other caches built on the activities (analysis, readiness) register here to be reset after a sync. */
export function onLoadInvalidate(fn: () => void): void {
    invalidators.push(fn);
}

export function invalidateLoadCache(): void {
    ratioCache = null;
    modelCache = null;
    for (const fn of invalidators) fn();
}

export async function loadOf(a: Pick<Activity, "trainingLoad" | "trimp">): Promise<number | null> {
    if (a.trainingLoad !== null && a.trainingLoad > 0) return a.trainingLoad;
    return a.trimp !== null ? a.trimp * (await trimpToLoad()) : null;
}

export const effortOf = (load: number | null) => (load === null ? null : Math.round(100 * (1 - Math.exp(-load / EFFORT_SCALE))) / 10);

/** Adds `effort` (session, 0-10) and `load` (7 days ending with the session, on 20) to activities sent by the API. */
export async function withEffort<T extends Activity>(activities: T[]) {
    return Promise.all(activities.map(async (a) => ({ ...a.toJSON(), effort: effortOf(await loadOf(a)), load: await sessionLoad(a.id) })));
}

/** Computes the TRIMP of activities stored before this feature existed. Local only, no Garmin call. */
export async function refreshTrimps(): Promise<void> {
    if ((await getSetting("trimpVersion")) === "1") return;
    const refs = await hrRefs();
    const activities = await Activity.findAll({ attributes: ["id", "averageHr", "duration"] });
    for (const a of activities) {
        const detail = await ActivityDetail.findByPk(a.id, { attributes: ["samples"] });
        await Activity.update({ trimp: computeTrimp(detail?.samples ?? [], a.averageHr, a.duration, refs) }, { where: { id: a.id } });
    }
    invalidateLoadCache();
    await setSetting("trimpVersion", "1");
}

export type FatigueZone = "fresh" | "optimal" | "high" | "overload";

const advice: Record<FatigueZone, string> = {
    fresh: "Bien reposé : bon moment pour une séance dure ou une course.",
    optimal: "Charge équilibrée : tu progresses sans t'épuiser.",
    high: "Charge élevée : privilégie le footing facile dans les prochains jours.",
    overload: "Surcharge : prévois du repos ou une semaine allégée.",
};

function zoneOf(fatigue: number): FatigueZone {
    if (fatigue < 3) return "fresh";
    if (fatigue < 7) return "optimal";
    if (fatigue < 8.5) return "high";
    return "overload";
}

const percentile = (values: number[], p: number) => {
    const sorted = values.filter((v) => v > 0).sort((x, y) => x - y);
    return sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))] : 1;
};

export interface SessionLoad {
    /** Load of the 7 days ending with this session (session included), on 20 */
    charge: number;
    weekKm: number;
    weekSessions: number;
}

interface DayRow {
    date: string;
    load: number;
    week: number;
    month: number;
    weekKm: number;
    monthKm: number;
    weekSessions: number;
    monthSessions: number;
}

const round = (v: number) => Math.round(v * 10) / 10;
const on20 = (v: number, ref: number) => round(Math.min((20 * v) / ref, 20));

// The promise is cached, not the value: concurrent callers share one computation instead of each starting their own
let modelCache: { at: number; value: ReturnType<typeof computeModel> } | null = null;

/** Whole-history computation, shared by the daily series and the per-session load (cached until the next sync). */
async function model() {
    if (!modelCache || Date.now() - modelCache.at > 10 * 60_000) modelCache = { at: Date.now(), value: computeModel() };
    return modelCache.value;
}

async function computeModel() {
    const activities = await Activity.findAll({ attributes: ["id", "startTimeLocal", "trainingLoad", "trimp", "distance"], order: [["startTimeLocal", "ASC"]] });
    const loads = await Promise.all(activities.map(async (a) => (await loadOf(a)) ?? 0));

    const perDay = new Map<string, { load: number; km: number; sessions: number }>();
    activities.forEach((a, i) => {
        const key = a.startTimeLocal.slice(0, 10);
        const day = perDay.get(key) ?? { load: 0, km: 0, sessions: 0 };
        day.load += loads[i];
        day.km += (a.distance ?? 0) / 1000;
        day.sessions += 1;
        perDay.set(key, day);
    });

    // One row per calendar day, rest days included, with rolling 7 / 28-day sums
    const rows: DayRow[] = [];
    if (activities.length) {
        const day = new Date(`${activities[0].startTimeLocal.slice(0, 10)}T12:00:00`);
        const end = toDateKey(new Date());
        const daily: { load: number; km: number; sessions: number }[] = [];
        const sum = (n: number, pick: (d: { load: number; km: number; sessions: number }) => number) => daily.slice(-n).reduce((t, d) => t + pick(d), 0);
        for (let key = toDateKey(day); key <= end; day.setDate(day.getDate() + 1), key = toDateKey(day)) {
            daily.push(perDay.get(key) ?? { load: 0, km: 0, sessions: 0 });
            rows.push({
                date: key,
                load: daily[daily.length - 1].load,
                week: sum(WEEK, (d) => d.load),
                month: sum(MONTH, (d) => d.load),
                weekKm: sum(WEEK, (d) => d.km),
                monthKm: sum(MONTH, (d) => d.km),
                weekSessions: sum(WEEK, (d) => d.sessions),
                monthSessions: sum(MONTH, (d) => d.sessions),
            });
        }
    }
    const weekRef = percentile(
        rows.map((r) => r.week),
        0.99,
    );
    const monthRef = percentile(
        rows.map((r) => r.month),
        0.99,
    );

    // Per session: the 6 previous calendar days plus that day up to this session (sliding window)
    const sessions = new Map<string, SessionLoad>();
    let left = 0;
    let load = 0;
    let km = 0;
    activities.forEach((a, i) => {
        const start = new Date(`${a.startTimeLocal.slice(0, 10)}T12:00:00`);
        start.setDate(start.getDate() - (WEEK - 1));
        const from = toDateKey(start);
        load += loads[i];
        km += (a.distance ?? 0) / 1000;
        while (activities[left].startTimeLocal.slice(0, 10) < from) {
            load -= loads[left];
            km -= (activities[left].distance ?? 0) / 1000;
            left++;
        }
        sessions.set(String(a.id), { charge: on20(load, weekRef), weekKm: Math.round(km), weekSessions: i - left + 1 });
    });

    return { rows, weekRef, monthRef, sessions };
}

export async function sessionLoad(id: number | string): Promise<SessionLoad | null> {
    return (await model()).sessions.get(String(id)) ?? null;
}

function fatigueOf(r: DayRow, weekRef: number): number | null {
    // The ratio means nothing without a base month (start of history, long break)
    const avgWeek = r.month / 4;
    return avgWeek > weekRef * 0.1 ? round(Math.min(Math.max((r.week / avgWeek - 0.6) * 10, 0), 10)) : null;
}

/** 7-day load (on 20), fatigue and the day's own load at the end of `date` (YYYY-MM-DD). */
export async function loadOnDay(date: string): Promise<{ week: number; month: number; fatigue: number | null; dayLoad: number } | null> {
    const { rows, weekRef, monthRef } = await model();
    const row = rows.find((r) => r.date === date);
    return row ? { week: on20(row.week, weekRef), month: on20(row.month, monthRef), fatigue: fatigueOf(row, weekRef), dayLoad: row.load } : null;
}

/** Share of the 7-day load scale (on 20) that a raw load represents, uncapped. */
export async function loadPoints(load: number): Promise<number> {
    return round((20 * load) / (await model()).weekRef);
}

/** Daily 7-day / 4-week load and fatigue, returning the last `days`. */
export async function loadSeries(days: number) {
    const { rows, weekRef, monthRef } = await model();
    if (!rows.length) return { today: null, series: [] };

    const series = rows.slice(-days).map((r) => ({ date: r.date, effort: r.load ? effortOf(r.load) : null, week: on20(r.week, weekRef), month: on20(r.month, monthRef), fatigue: fatigueOf(r, weekRef) }));

    const last = series[series.length - 1];
    const lastRow = rows[rows.length - 1];
    const weekAgo = series[series.length - 8];
    const zone = last.fatigue === null ? null : zoneOf(last.fatigue);
    return {
        today: {
            week: last.week,
            month: last.month,
            weekTrend: weekAgo ? round(last.week - weekAgo.week) : null,
            weekKm: Math.round(lastRow.weekKm),
            monthKm: Math.round(lastRow.monthKm),
            weekSessions: lastRow.weekSessions,
            monthSessions: lastRow.monthSessions,
            fatigue: last.fatigue,
            zone,
            advice: zone ? advice[zone] : "Pas assez d'entraînement récent pour estimer la fatigue.",
        },
        series,
    };
}

// Reference heart rate for the efficiency indicator: a typical easy-run value
const EFFICIENCY_HR = 150;
const MIN_RUNS_PER_MONTH = 4;

/**
 * Long-term view, one point per month over the whole history:
 * - charge: average 4-week load (on 20), how much you trained
 * - efficiency: speed you ran at 150 bpm on steady runs (median of the month), how fit you were.
 *   Steady = running, no interval, 30 min to 2 h, average HR 130-165 (speed scaled linearly to 150 bpm).
 * VO2 max is not used: the watch reports it rounded to the unit, it barely moves.
 */
export async function loadHistory() {
    const { rows, monthRef } = await model();
    const runs = await Activity.findAll({
        where: { type: { [Op.like]: "%running%" } },
        attributes: ["startTimeLocal", "distance", "duration", "averageSpeed", "averageHr", "intervalTitle"],
    });

    const months = new Map<string, { loads: number[]; km: number; runs: number; efficiency: number[] }>();
    for (const r of rows) {
        const key = r.date.slice(0, 7);
        const m = months.get(key) ?? { loads: [], km: 0, runs: 0, efficiency: [] };
        m.loads.push(r.month);
        months.set(key, m);
    }
    for (const r of runs) {
        const m = months.get(r.startTimeLocal.slice(0, 7));
        if (!m) continue;
        m.km += (r.distance ?? 0) / 1000;
        m.runs += 1;
        const steady = !r.intervalTitle && (r.duration ?? 0) >= 1800 && (r.duration ?? 0) <= 7200;
        if (steady && r.averageSpeed && r.averageHr && r.averageHr >= 130 && r.averageHr <= 165) {
            m.efficiency.push((r.averageSpeed * EFFICIENCY_HR) / r.averageHr);
        }
    }

    const median = (values: number[]) => {
        const sorted = [...values].sort((a, b) => a - b);
        return sorted[Math.floor(sorted.length / 2)];
    };
    const points = [...months.entries()].map(([month, m]) => ({
        month,
        charge: on20(m.loads.reduce((a, b) => a + b, 0) / m.loads.length, monthRef),
        // m/s at 150 bpm, shown as a pace
        efficiency: m.efficiency.length >= MIN_RUNS_PER_MONTH ? Math.round(median(m.efficiency) * 1000) / 1000 : null,
        km: Math.round(m.km),
        runs: m.runs,
    }));
    const best = (pick: (p: (typeof points)[number]) => number | null) => points.reduce<(typeof points)[number] | null>((b, p) => ((pick(p) ?? -1) > (b ? (pick(b) ?? -1) : -1) ? p : b), null);
    return { months: points, efficiencyHr: EFFICIENCY_HR, peakCharge: best((p) => p.charge), peakEfficiency: best((p) => p.efficiency) };
}
