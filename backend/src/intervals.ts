import type { Lap } from "./db.js";

export type LapRole = "warmup" | "work" | "recovery" | "cooldown" | "other";

export interface LapInfo {
    lap: Lap;
    role: LapRole;
    /** Index in `groups` for work laps */
    group: number | null;
    /** Repetition number inside its group (1-based) */
    rep: number | null;
}

export interface Recovery {
    duration: number;
    distance: number;
    speed: number;
    /** "2 min 30" or "200 m", depending on whether recoveries were timed or measured */
    label: string;
}

export interface RepGroup {
    label: string;
    timeBased: boolean;
    reps: LapInfo[];
    /** Total distance / total time, the true average over the reps */
    avgSpeed: number;
    avgDuration: number;
    avgDistance: number;
    avgHr: number | null;
    bestSpeed: number;
    worstSpeed: number;
    recovery: Recovery | null;
}

export interface IntervalAnalysis {
    title: string;
    /** True when the watch ran a structured workout (laps tagged by Garmin) */
    structured: boolean;
    groups: RepGroup[];
    laps: LapInfo[];
}

// Minimum speed ratio between efforts and recoveries for manual laps (auto-lap runs stay below)
const MIN_GAP = 1.12;
// Reps of the same type: distances within 15 %
const SAME_DISTANCE = 0.15;

const mean = (values: number[]) => values.reduce((a, b) => a + b, 0) / values.length;
const median = (values: number[]) => {
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};
const cv = (values: number[]) => {
    const m = mean(values);
    return m ? Math.sqrt(mean(values.map((v) => (v - m) ** 2))) / m : 0;
};

function roundDistance(d: number): number {
    const step = d >= 2950 ? 500 : d >= 950 ? 100 : d >= 150 ? 50 : 10;
    return Math.round(d / step) * step;
}

function nominalDistance(d: number): string {
    const r = roundDistance(d);
    return r >= 3000 ? `${String(r / 1000).replace(".", ",")}\u00a0km` : `${r}\u00a0m`;
}

/** Distance reps land close to a round distance (1000 m, 400 m…), timed reps usually do not. */
const onRoundDistance = (d: number) => Math.abs(d - roundDistance(d)) / roundDistance(d) <= 0.025;

function nominalDuration(s: number): string {
    const rounded = Math.round(s / 5) * 5;
    const m = Math.floor(rounded / 60);
    const sec = rounded % 60;
    // Non-breaking spaces: "1 min 30" never wraps in the middle
    if (!m) return `${sec}\u00a0s`;
    return sec ? `${m}\u00a0min\u00a0${String(sec).padStart(2, "0")}` : `${m}\u00a0min`;
}

const isTiny = (l: Lap) => (l.distance ?? 0) < 30 && (l.duration ?? 0) < 15;
const speedOf = (l: Lap) => (l.duration ? (l.distance ?? 0) / l.duration : 0);

/** Roles from Garmin's tags, when the watch ran a structured workout. */
function structuredRoles(laps: Lap[]): LapRole[] | null {
    if (!laps.some((l) => l.intensityType)) return null;
    const roles = laps.map((l): LapRole => {
        if (isTiny(l)) return "other";
        switch (l.intensityType) {
            case "WARMUP":
                return "warmup";
            case "COOLDOWN":
                return "cooldown";
            case "REST":
            case "RECOVERY":
                return "recovery";
            case "ACTIVE":
            case "INTERVAL":
                return "work";
            default:
                return "other";
        }
    });
    return roles.filter((r) => r === "work").length >= 2 ? roles : null;
}

/** Manual laps: efforts are the laps clearly faster than the rest (largest speed gap). */
function inferredRoles(laps: Lap[]): LapRole[] | null {
    const candidates = laps.filter((l) => !isTiny(l) && speedOf(l) > 0);
    const speeds = [...new Set(candidates.map(speedOf))].sort((a, b) => b - a);
    let threshold = 0;
    let bestGap = 0;
    for (let i = 0; i < speeds.length - 1; i++) {
        const gap = speeds[i] / speeds[i + 1];
        if (gap > bestGap) {
            bestGap = gap;
            threshold = (speeds[i] + speeds[i + 1]) / 2;
        }
    }
    const fast = candidates.filter((l) => speedOf(l) > threshold).length;
    if (bestGap < MIN_GAP || fast < 2 || fast === candidates.length) return null;

    const roles = laps.map((l): LapRole => (isTiny(l) ? "other" : speedOf(l) > threshold ? "work" : "recovery"));
    const first = roles.indexOf("work");
    const last = roles.lastIndexOf("work");
    return roles.map((r, i) => (r !== "recovery" ? r : i < first ? "warmup" : i > last ? "cooldown" : "recovery"));
}

/** Recovery between reps: timed (most common) or over a distance when the distances are round and steady. */
function describeRecovery(laps: Lap[]): Recovery {
    const durations = laps.map((l) => l.duration!);
    const distances = laps.map((l) => l.distance!);
    const duration = mean(durations);
    const distance = mean(distances);
    const timed = durations.every((t) => Math.abs(t - Math.round(t / 15) * 15) <= 1);
    // A single round distance may be chance: need at least two matching recoveries
    const measured = !timed && laps.length > 1 && distances.every(onRoundDistance) && cv(distances) < 0.05;
    return {
        duration,
        distance,
        speed: distances.reduce((a, b) => a + b, 0) / durations.reduce((a, b) => a + b, 0),
        // Manual laps vary (the last recovery before a new block is often longer): median, rounded to 15 s
        label: measured ? nominalDistance(median(distances)) : nominalDuration(timed ? duration : Math.round(median(durations) / 15) * 15),
    };
}

/**
 * Detects an interval session from the watch laps and groups the repetitions by type
 * (e.g. 3 × 1000 m + 4 × 200 m), with averages per type. Returns null for a steady run.
 */
export function analyzeIntervals(laps: Lap[]): IntervalAnalysis | null {
    const usable = laps.filter((l) => l.distance !== null && l.duration);
    if (usable.length < 3) return null;

    const tagged = structuredRoles(usable);
    const roles = tagged ?? inferredRoles(usable);
    if (!roles) return null;

    const infos: LapInfo[] = usable.map((lap, i) => ({ lap, role: roles[i], group: null, rep: null }));

    // Group efforts: by workout step when known, otherwise by similar distance
    const buckets: { step: number | null; distances: number[]; reps: LapInfo[] }[] = [];
    for (const info of infos) {
        if (info.role !== "work") continue;
        const d = info.lap.distance!;
        const step = tagged ? (info.lap.stepIndex ?? null) : null;
        let bucket = step !== null ? buckets.find((b) => b.step === step) : buckets.find((b) => b.step === null && Math.abs(d - mean(b.distances)) / mean(b.distances) < SAME_DISTANCE);
        if (!bucket) {
            bucket = { step, distances: [], reps: [] };
            buckets.push(bucket);
        }
        bucket.distances.push(d);
        bucket.reps.push(info);
        info.group = buckets.indexOf(bucket);
        info.rep = bucket.reps.length;
    }

    const buildGroup = (reps: LapInfo[]): RepGroup => {
        const durations = reps.map((r) => r.lap.duration!);
        const distances = reps.map((r) => r.lap.distance!);
        // Timed reps (e.g. 30 s / 30 s): durations sit on round values and vary less than distances
        const roundDurations = durations.every((t) => Math.abs(t - Math.round(t / 15) * 15) <= 1);
        const timeBased = reps.length > 1 && roundDurations && cv(durations) < cv(distances) && !distances.every(onRoundDistance);
        const speeds = reps.map((r) => speedOf(r.lap));
        const hrs = reps.map((r) => r.lap.averageHr).filter((h): h is number => h !== null);

        // Recovery = the recovery lap right after each rep
        const recoveries = reps
            .map((r) => infos[infos.indexOf(r) + 1])
            .filter((next): next is LapInfo => next?.role === "recovery")
            .map((next) => next.lap);

        return {
            label: timeBased ? nominalDuration(mean(durations)) : nominalDistance(mean(distances)),
            timeBased,
            reps,
            avgSpeed: distances.reduce((a, c) => a + c, 0) / durations.reduce((a, c) => a + c, 0),
            avgDuration: mean(durations),
            avgDistance: mean(distances),
            avgHr: hrs.length ? Math.round(mean(hrs)) : null,
            bestSpeed: Math.max(...speeds),
            worstSpeed: Math.min(...speeds),
            recovery: recoveries.length ? describeRecovery(recoveries) : null,
        };
    };

    // Some watches store each rep of a workout as its own step: merge groups that end up with the same label
    const groups: RepGroup[] = [];
    for (const group of buckets.map((b) => buildGroup(b.reps))) {
        const twin = groups.findIndex((g) => g.label === group.label && g.timeBased === group.timeBased);
        if (twin === -1) groups.push(group);
        else groups[twin] = buildGroup([...groups[twin].reps, ...group.reps].sort((x, y) => infos.indexOf(x) - infos.indexOf(y)));
    }
    groups.forEach((g, gi) =>
        g.reps.forEach((r, ri) => {
            r.group = gi;
            r.rep = ri + 1;
        }),
    );

    return {
        title: groups.map((g) => `${g.reps.length > 1 ? `${g.reps.length} × ` : ""}${g.label}${g.recovery ? ` (r\u00a0: ${g.recovery.label})` : ""}`).join(" + "),
        structured: Boolean(tagged),
        groups,
        laps: infos,
    };
}
