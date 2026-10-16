import type { IntervalAnalysis } from "./intervals";

export type StepStatus = "pending" | "running" | "success" | "error" | "skipped";
export type SyncRunStatus = "running" | "success" | "partial" | "error";
export type StepKey = "auth" | "activities" | "details" | "wellness" | "fitness";
export type SyncMode = "today" | "full";

export interface SyncStep {
    key: StepKey;
    label: string;
    status: StepStatus;
    count: number;
    total: number | null;
    error: string | null;
}

export interface SyncRun {
    id: number;
    trigger: "manual" | "auto";
    mode: SyncMode;
    status: SyncRunStatus;
    startedAt: string;
    finishedAt: string | null;
    steps: SyncStep[];
    error: string | null;
}

/** Saved choices, both off by default */
export interface Prefs {
    autoSync: boolean;
}

export interface Coverage {
    done: number;
    total: number;
}

export interface SyncStatus {
    configured: boolean;
    mock: boolean;
    current: SyncRun | null;
    lastRun: SyncRun | null;
    lastSuccessAt: string | null;
    nextAutoSyncAt: string | null;
    intervalMinutes: number;
    scope: { activitiesLimit: number; wellnessDays: number; detailsPerRun: number; wellnessBackfillPerRun: number };
    catchUp: {
        coverage: { activities: Coverage; details: Coverage; wellness: Coverage };
        runsLeft: number;
        avgRunSeconds: number;
        etaSeconds: number;
    };
    catchUpIntervalMinutes: number;
    schedule: { windows: string[]; intervalMinutes: number; nextAutoSyncAt: string | null; reason: "catch-up" | "window" | "next-window" | null };
    prefs: Prefs;
}

export interface Activity {
    id: number;
    name: string;
    type: string;
    startTimeLocal: string;
    locationName: string | null;
    distance: number | null;
    duration: number | null;
    movingDuration: number | null;
    averageSpeed: number | null;
    maxSpeed: number | null;
    averageHr: number | null;
    maxHr: number | null;
    calories: number | null;
    elevationGain: number | null;
    elevationLoss: number | null;
    cadence: number | null;
    maxCadence: number | null;
    strideLength: number | null;
    groundContactTime: number | null;
    verticalOscillation: number | null;
    verticalRatio: number | null;
    avgPower: number | null;
    normPower: number | null;
    minTemperature: number | null;
    maxTemperature: number | null;
    steps: number | null;
    vo2Max: number | null;
    aerobicTe: number | null;
    anaerobicTe: number | null;
    teLabel: string | null;
    trainingLoad: number | null;
    hrZones: number[] | null;
    fastest1k: number | null;
    lapCount: number | null;
    hasPolyline: boolean;
    detailsSyncedAt: string | null;
    /** Detected interval workout, e.g. "3 × 1000 m (r : 2 min 30)" */
    intervalTitle: string | null;
    /** Kind chosen by hand, wins over the detection */
    manualCategory: ActivityCategory | null;
    category: ActivityCategory | null;
    /** Session effort, 0-10 */
    effort: number | null;
    /** Load of the 7 days ending with this session (session included), on 20 */
    load: { charge: number; weekKm: number; weekSessions: number } | null;
}

/** Filters of the activity list */
export type ActivityFilter = "all" | ActivityCategory;

export const ACTIVITY_FILTERS: ActivityFilter[] = ["all", "competition", "interval", "long", "easy", "strength", "other"];

/** Kind of session: the one chosen by hand wins, then the detection (see backend categories.ts) */
export type ActivityCategory = "competition" | "interval" | "long" | "strength" | "easy" | "other";

export type FatigueZone = "fresh" | "optimal" | "high" | "overload";

export interface LoadMonth {
    month: string; // YYYY-MM
    /** Average 4-week load that month, on 20 */
    charge: number;
    /** Speed (m/s) at the reference heart rate on steady runs, median of the month */
    efficiency: number | null;
    km: number;
    runs: number;
}

export interface LoadHistory {
    months: LoadMonth[];
    efficiencyHr: number;
    peakCharge: LoadMonth | null;
    peakEfficiency: LoadMonth | null;
}

export interface LoadSummary {
    today: {
        /** Load over the last 7 days / 4 weeks, on 20 (20 = the heaviest weeks / months of the history) */
        week: number;
        month: number;
        weekTrend: number | null;
        weekKm: number;
        monthKm: number;
        weekSessions: number;
        monthSessions: number;
        fatigue: number | null;
        zone: FatigueZone | null;
        advice: string;
    } | null;
    series: { date: string; effort: number | null; week: number; month: number; fatigue: number | null }[];
}

export interface Lap {
    distance: number | null;
    duration: number | null;
    averageSpeed: number | null;
    averageHr: number | null;
    maxHr: number | null;
    elevationGain: number | null;
    elevationLoss: number | null;
    cadence: number | null;
    intensityType?: string | null;
    stepIndex?: number | null;
}

export interface Sample {
    d: number | null;
    t: number | null;
    hr: number | null;
    speed: number | null;
    ele: number | null;
    cad: number | null;
}

export interface ActivityWithDetail extends Activity {
    detail: { laps: Lap[]; track: [number, number][]; samples: Sample[] } | null;
    intervals: IntervalAnalysis | null;
    analysis: SessionAnalysis | null;
}

export type FactorKey = "sleep" | "sleepScore" | "hrv" | "restingHr" | "bodyBattery" | "stress" | "fatigue";
export type FactorStatus = "good" | "normal" | "low";

/** One readiness factor, compared with the athlete's own usual values */
export interface Factor {
    key: FactorKey;
    label: string;
    value: number;
    display: string;
    baseline: number | null;
    baselineDisplay: string | null;
    /** Signed deviation, positive = favorable */
    z: number;
    status: FactorStatus;
    note: string;
}

export interface Readiness {
    date: string;
    score: number;
    label: string;
    factors: Factor[];
}

export type VerdictKey = "excellent" | "good" | "standard" | "controlled" | "below" | "hard" | "first";

export interface Contribution {
    key: string;
    label: string;
    detail: string;
    /** Estimated effect on the pace, % (negative = slower) */
    effectPct: number;
    effectSecPerKm: number;
    source: "perso" | "études";
}

export interface Correlation {
    key: string;
    label: string;
    n: number;
    rho: number;
    p: number;
    strength: "nette" | "légère" | "aucune";
    splitEffectPct: number;
    text: string;
}

export interface SessionAnalysis {
    verdict: VerdictKey;
    verdictLabel: string;
    paceIndex: number | null;
    paceDeltaSecPerKm: number | null;
    hrDelta: number | null;
    fadePct: number | null;
    hrRecovery: { avg: number; first: number; last: number } | null;
    reps: { group: number; rep: number; speed: number; reference: number | null; deltaSecPerKm: number | null; hr: number | null }[];
    factors: Factor[];
    contributions: Contribution[];
    correlations: Correlation[];
    sampleSize: number;
    paragraphs: string[];
    summary: string;
}

export type SessionCategory = ActivityCategory | "tempo";

export interface WindowSession {
    id: number;
    startTimeLocal: string;
    title: string;
    category: SessionCategory;
    km: number;
    /** Share of the 7-day load (on 20) */
    points: number;
}

export interface LoadWindow {
    from: string;
    to: string;
    points: number;
    km: number;
    sessions: WindowSession[];
}

export interface LoadComparison {
    current: LoadWindow;
    previous: LoadWindow[];
    avgPoints: number;
    avgKm: number;
    delta: number;
    categories: { category: SessionCategory; label: string; count: number; avgCount: number; points: number; avgPoints: number; delta: number }[];
    explanation: string[];
}

export interface Briefing {
    name: string | null;
    /** Profile picture URL (Garmin profile) */
    photo: string | null;
    headline: string;
    readiness: Readiness | null;
    km: { value: number; lastWeek: number; sessions: number };
    load: { value: number; previous: number; delta: number; fatigue: number | null };
    comparison: LoadComparison;
    lastSession: { id: number; startTimeLocal: string; title: string; verdict: VerdictKey; verdictLabel: string; summary: string } | null;
}

export interface DailyMetric {
    date: string;
    steps: number | null;
    stepGoal: number | null;
    distanceMeters: number | null;
    totalCalories: number | null;
    activeCalories: number | null;
    floors: number | null;
    moderateMinutes: number | null;
    vigorousMinutes: number | null;
    restingHr: number | null;
    minHr: number | null;
    maxHr: number | null;
    avgStress: number | null;
    maxStress: number | null;
    bodyBatteryHigh: number | null;
    bodyBatteryLow: number | null;
    bodyBatteryCharged: number | null;
    bodyBatteryDrained: number | null;
    respiration: number | null;
    sleepSeconds: number | null;
    deepSleepSeconds: number | null;
    lightSleepSeconds: number | null;
    remSleepSeconds: number | null;
    awakeSleepSeconds: number | null;
    sleepScore: number | null;
    hrvNight: number | null;
    hrvWeekly: number | null;
    hrvStatus: string | null;
    weightKg: number | null;
}

/** A run that looks like a race, with the rules it meets */
export interface ReviewItem {
    id: number;
    name: string;
    startTimeLocal: string;
    distance: number | null;
    duration: number | null;
    averageSpeed: number | null;
    averageHr: number | null;
    detected: ActivityCategory;
    score: number;
    reasons: string[];
}

/** One aggregated indicator of the health page */
export interface HealthMetric {
    key: "recovery" | "sleep" | "hrv" | "fitness" | "fatigue";
    label: string;
    value: string;
    unit: string | null;
    status: FactorStatus;
    statusLabel: string;
    delta: string | null;
    sentence: string;
    series: { date: string; value: number | null }[];
    /** Trend drawn upside down (pace), so up always reads as progress */
    inverted: boolean;
    /** Raw value placed on the scale of the explanation */
    numeric: number;
    /** Personal normal range (HRV) */
    band?: [number, number];
}

export interface Fitness {
    date: string;
    vo2Max: number | null;
    trainingStatus: string | null;
    acuteLoad: number | null;
    chronicLoad: number | null;
    chronicLoadMin: number | null;
    chronicLoadMax: number | null;
    acwr: number | null;
    acwrStatus: string | null;
    loadAerobicLow: number | null;
    loadAerobicHigh: number | null;
    loadAnaerobic: number | null;
    loadBalance: string | null;
    race5k: number | null;
    race10k: number | null;
    raceHalf: number | null;
    raceMarathon: number | null;
}

export interface WeekSummary {
    week: string;
    distance: number;
    duration: number;
    elevation: number;
    count: number;
}

export class ApiError extends Error {
    readonly status: number;

    constructor(message: string, status: number) {
        super(message);
        this.status = status;
    }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`/api${path}`, init);
    const body = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(body?.error ?? `Erreur ${res.status}`, res.status);
    return body as T;
}

export const api = {
    syncStatus: () => request<SyncStatus>("/sync/status"),
    startSync: (mode: SyncMode = "today") =>
        request<SyncRun>("/sync", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ mode }) }),
    setPrefs: (patch: Partial<Prefs>) =>
        request<Prefs>("/preferences", { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(patch) }),
    setCategory: (id: number, category: ActivityCategory | null) =>
        request<{ category: ActivityCategory; manual: boolean }>(`/activities/${id}/category`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ category }) }),
    review: () => request<ReviewItem[]>("/review"),
    resetData: () => request<{ ok: boolean; run: SyncRun | null }>("/data", { method: "DELETE" }),
    syncHistory: (limit = 20) => request<SyncRun[]>(`/sync/history?limit=${limit}`),
    activities: (limit = 20, offset = 0, category: ActivityFilter = "all") =>
        request<{ items: Activity[]; total: number; counts?: Record<ActivityFilter, number> }>(`/activities?limit=${limit}&offset=${offset}&category=${category}`),
    activity: (id: string) => request<ActivityWithDetail>(`/activities/${encodeURIComponent(id)}`),
    daily: (days = 14) => request<DailyMetric[]>(`/daily?days=${days}`),
    summary: (weeks = 8, sport?: "running") => request<{ weeks: WeekSummary[]; lastRun: Activity | null }>(`/summary?weeks=${weeks}${sport ? `&sport=${sport}` : ""}`),
    load: (days = 90) => request<LoadSummary>(`/load?days=${days}`),
    loadHistory: () => request<LoadHistory>("/load/history"),
    briefing: () => request<Briefing>("/briefing"),
    healthSummary: (days = 30) => request<HealthMetric[]>(`/health/summary?days=${days}`),
    fitness: (days = 90) => request<{ latest: Fitness | null; history: Fitness[] }>(`/fitness?days=${days}`),
};
