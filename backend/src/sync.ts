import { Op } from "sequelize";
import { config } from "./config.js";
import { Activity, ActivityDetail, DAILY_VERSION, type Lap, DailyMetric, FitnessSnapshot, getSetting, sequelize, Setting, setSetting, SyncRun, type StepKey, type SyncMode, type SyncStep } from "./db.js";
import { createGarminSource, toDateKey, type ActivityInput, type GarminSource } from "./garmin.js";
import { analyzeIntervals } from "./intervals.js";
import { getPrefs, PREF_PREFIX } from "./prefs.js";
import { refreshCategories } from "./categories.js";
import { computeTrimp, hrRefs, invalidateLoadCache } from "./load.js";
import { formatWindows, inWindow, nextWindowStart } from "./schedule.js";

const PAGE_SIZE = 100;
const INCREMENTAL_PAGE_SIZE = 20;
const REMOTE_COUNT_KEY = "remoteActivityCount";

let current: SyncRun | null = null;
let nextAutoSyncAt: Date | null = null;
let nextAutoReason: "catch-up" | "window" | "next-window" | null = null;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const pause = () => sleep(config.sync.requestDelayMs);
const errorMessage = (err: unknown) => (err instanceof Error ? err.message : String(err));

function daysAgo(n: number): Date {
    const d = new Date();
    d.setDate(d.getDate() - n);
    return d;
}

function initialSteps(): SyncStep[] {
    const step = (key: StepKey, label: string): SyncStep => ({ key, label, status: "pending", count: 0, total: null, error: null });
    return [step("auth", "Connexion à Garmin"), step("activities", "Liste des activités"), step("details", "Détails et tracés GPS"), step("wellness", "Santé (sommeil, VFC, stress, Body Battery…)"), step("fitness", "Forme (statut, charge, prédictions)")];
}

/** Steps are a JSON column: reassign a new array so Sequelize sees the change. */
async function updateStep(run: SyncRun, key: StepKey, patch: Partial<SyncStep>): Promise<void> {
    run.steps = run.steps.map((s) => (s.key === key ? { ...s, ...patch } : s));
    await run.save();
}

async function upsertActivities(page: ActivityInput[]): Promise<void> {
    if (page.length === 0) return;
    await Activity.bulkCreate(page, { updateOnDuplicate: Object.keys(page[0]) as (keyof ActivityInput)[] });
}

/**
 * While the local history is incomplete, the whole list is fetched (cheap: 100 activities per call).
 * Once complete, only the newest pages are read, until an already known activity shows up.
 */
async function syncActivities(source: GarminSource, run: SyncRun): Promise<void> {
    const remote = await source.countActivities();
    const target = config.sync.activitiesLimit ? Math.min(config.sync.activitiesLimit, remote) : remote;
    await setSetting(REMOTE_COUNT_KEY, String(target));
    const before = await Activity.count();

    if (before < target) {
        await updateStep(run, "activities", { total: target });
        for (let start = 0; start < target; start += PAGE_SIZE) {
            const page = await source.fetchActivities(start, Math.min(PAGE_SIZE, target - start));
            if (page.length === 0) break;
            await upsertActivities(page);
            await updateStep(run, "activities", { count: Math.min(start + page.length, target) });
            await pause();
        }
    } else {
        for (let start = 0; start < target; start += INCREMENTAL_PAGE_SIZE) {
            const page = await source.fetchActivities(start, INCREMENTAL_PAGE_SIZE);
            if (page.length === 0) break;
            const known = await Activity.count({ where: { id: page.map((a) => a.id) } });
            await upsertActivities(page);
            await updateStep(run, "activities", { count: (await Activity.count()) - before });
            if (known > 0) break;
            await pause();
        }
    }
    // Final count = activities that were new for this run
    await updateStep(run, "activities", { count: (await Activity.count()) - before, total: null });
}

export function intervalTitleFor(type: string, laps: Lap[]): string | null {
    return type.includes("running") ? (analyzeIntervals(laps)?.title ?? null) : null;
}

/** Computes interval titles for details already stored (after an analysis change). Local only, no Garmin call. */
export async function refreshIntervalTitles(): Promise<void> {
    const VERSION = "4";
    if ((await getSetting("intervalsVersion")) === VERSION) return;
    const details = await ActivityDetail.findAll({ attributes: ["activityId", "laps"] });
    const types = new Map((await Activity.findAll({ attributes: ["id", "type"] })).map((a) => [String(a.id), a.type]));
    for (const d of details) {
        const type = types.get(String(d.activityId));
        if (type) await Activity.update({ intervalTitle: intervalTitleFor(type, d.laps) }, { where: { id: d.activityId } });
    }
    await setSetting("intervalsVersion", VERSION);
}

/** Laps, chart samples and GPS track: two calls per activity, so done in batches, newest first. */
async function syncDetails(source: GarminSource, run: SyncRun): Promise<void> {
    // "today" mode: only the activities of today and yesterday, the history waits for a full sync
    const recentOnly = run.mode === "today" ? { startTimeLocal: { [Op.gte]: toDateKey(daysAgo(1)) } } : {};
    const pending = await Activity.findAll({
        where: { detailsSyncedAt: null, ...recentOnly },
        order: [["startTimeLocal", "DESC"]],
        limit: config.sync.detailsPerRun,
        attributes: ["id", "type", "averageHr", "duration"],
    });
    const refs = await hrRefs();
    await updateStep(run, "details", { total: pending.length });
    let done = 0;
    for (const { id, type, averageHr, duration } of pending) {
        const detail = await source.fetchActivityDetail(id);
        await ActivityDetail.upsert(detail);
        await Activity.update(
            { detailsSyncedAt: new Date(), intervalTitle: intervalTitleFor(type, detail.laps), trimp: computeTrimp(detail.samples, averageHr, duration, refs) },
            { where: { id } },
        );
        await updateStep(run, "details", { count: ++done });
        await pause();
    }
}

/** Recent days are always refreshed; older missing days are caught up in batches, newest first. */
async function syncWellness(source: GarminSource, run: SyncRun): Promise<void> {
    const { wellnessDays, wellnessRecentDays } = config.sync;
    const wellnessBackfillPerRun = run.mode === "today" ? 0 : config.sync.wellnessBackfillPerRun;
    const windowStart = toDateKey(daysAgo(wellnessDays - 1));
    const synced = await DailyMetric.findAll({
        where: { date: { [Op.gte]: windowStart }, version: { [Op.gte]: DAILY_VERSION } },
        attributes: ["date"],
    });
    const upToDate = new Set(synced.map((d) => d.date));

    const toFetch: Date[] = [];
    let backfill = 0;
    for (let i = 0; i < wellnessDays; i++) {
        const date = daysAgo(i);
        if (i < wellnessRecentDays) toFetch.push(date);
        else if (!upToDate.has(toDateKey(date)) && backfill < wellnessBackfillPerRun) {
            toFetch.push(date);
            backfill++;
        }
    }

    await updateStep(run, "wellness", { total: toFetch.length });
    let done = 0;
    for (const date of toFetch) {
        await DailyMetric.upsert(await source.fetchDay(date));
        await updateStep(run, "wellness", { count: ++done });
        await pause();
    }

    // Weight comes from a single range call, applied after the days so it is not overwritten
    const weights = await source.fetchWeights(daysAgo(wellnessDays - 1), new Date());
    for (const { date, weightKg } of weights) await DailyMetric.update({ weightKg }, { where: { date } });
}

async function syncFitness(source: GarminSource, run: SyncRun): Promise<void> {
    await updateStep(run, "fitness", { total: 1 });
    // Today's status stays empty until the watch syncs: fall back to yesterday's
    let snapshot = await source.fetchFitness(new Date());
    if (snapshot.trainingStatus === null && snapshot.vo2Max === null) {
        const yesterday = await source.fetchFitness(daysAgo(1));
        snapshot = { ...yesterday, race5k: snapshot.race5k ?? yesterday.race5k, race10k: snapshot.race10k ?? yesterday.race10k, raceHalf: snapshot.raceHalf ?? yesterday.raceHalf, raceMarathon: snapshot.raceMarathon ?? yesterday.raceMarathon };
        // Drop the empty row written for today by an earlier run
        await FitnessSnapshot.destroy({ where: { date: toDateKey(new Date()), trainingStatus: null, vo2Max: null } });
    }
    await FitnessSnapshot.upsert(snapshot);
    await updateStep(run, "fitness", { count: 1 });
}

async function runSync(run: SyncRun): Promise<void> {
    const source = createGarminSource();
    const tasks: [StepKey, () => Promise<void | "skipped">][] = [
        ["activities", () => syncActivities(source, run)],
        ["details", () => syncDetails(source, run)],
        ["wellness", () => syncWellness(source, run)],
        ["fitness", () => syncFitness(source, run)],
    ];

    try {
        await updateStep(run, "auth", { status: "running" });
        await source.authenticate();
        const firstName = source.firstName();
        if (firstName) await setSetting("profileName", firstName);
        const photo = source.photoUrl();
        if (photo) await setSetting("garminPhoto", photo);
        await updateStep(run, "auth", { status: "success", count: 1 });
    } catch (err) {
        await updateStep(run, "auth", { status: "error", error: errorMessage(err) });
        for (const [key] of tasks) await updateStep(run, key, { status: "skipped" });
        run.status = "error";
        run.error = `Connexion impossible : ${errorMessage(err)}`;
        return;
    }

    // One failing data type must not prevent the others from syncing
    let failures = 0;
    for (const [key, task] of tasks) {
        await updateStep(run, key, { status: "running" });
        try {
            const result = await task();
            await updateStep(run, key, { status: result === "skipped" ? "skipped" : "success" });
        } catch (err) {
            failures++;
            await updateStep(run, key, { status: "error", error: errorMessage(err) });
        }
    }
    run.status = failures === 0 ? "success" : failures === tasks.length ? "error" : "partial";
}

/** How much of the Garmin history is stored locally, for the completeness gauge. */
export async function getCoverage() {
    const localActivities = await Activity.count();
    const remote = Number((await getSetting(REMOTE_COUNT_KEY)) ?? localActivities);
    const windowStart = toDateKey(daysAgo(config.sync.wellnessDays - 1));
    return {
        activities: { done: Math.min(localActivities, remote), total: remote },
        details: { done: await Activity.count({ where: { detailsSyncedAt: { [Op.ne]: null } } }), total: localActivities },
        wellness: {
            done: await DailyMetric.count({ where: { date: { [Op.gte]: windowStart }, version: { [Op.gte]: DAILY_VERSION } } }),
            total: config.sync.wellnessDays,
        },
    };
}

type Coverage = Awaited<ReturnType<typeof getCoverage>>;

/** Syncs still needed to catch up the history (details and wellness are fetched in batches). */
function runsLeft(coverage: Coverage): number {
    const { detailsPerRun, wellnessBackfillPerRun } = config.sync;
    const details = Math.ceil((coverage.details.total - coverage.details.done) / Math.max(detailsPerRun, 1));
    const wellness = Math.ceil((coverage.wellness.total - coverage.wellness.done) / Math.max(wellnessBackfillPerRun, 1));
    const activities = coverage.activities.done < coverage.activities.total ? 1 : 0;
    return Math.max(details, wellness, activities, 0);
}

/** Time estimate for the history catch-up, from the duration of the last finished syncs. */
export async function getCatchUp() {
    const coverage = await getCoverage();
    const left = runsLeft(coverage);
    const recent = await SyncRun.findAll({
        where: { finishedAt: { [Op.ne]: null }, status: { [Op.in]: ["success", "partial"] } },
        order: [["startedAt", "DESC"]],
        limit: 5,
    });
    const durations = recent.map((r) => (r.finishedAt!.getTime() - r.startedAt.getTime()) / 1000);
    // Before any measured run: rough guess from the number of Garmin calls of a full batch
    const guess = ((config.sync.detailsPerRun * 2 + config.sync.wellnessBackfillPerRun * 3) * (config.sync.requestDelayMs + 400)) / 1000;
    const avgRunSeconds = durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : guess;
    const pauseSeconds = (left > 1 ? config.sync.catchUpIntervalMinutes : 0) * 60;
    return {
        coverage,
        runsLeft: left,
        avgRunSeconds: Math.round(avgRunSeconds),
        etaSeconds: left ? Math.round(left * avgRunSeconds + (left - 1) * pauseSeconds) : 0,
    };
}

// Checked against the wall clock every 30 s rather than one long setTimeout: timers follow the
// monotonic clock, which stops while the machine sleeps, so a long timer fires late after a suspend
let ticker: NodeJS.Timeout | undefined;

/**
 * Next automatic sync: every catch-up interval while the history is incomplete (any time of day),
 * then every interval inside the sync windows only, and nothing outside them.
 */
async function scheduleNext(): Promise<void> {
    const { intervalMinutes, catchUpIntervalMinutes, windows } = config.sync;
    nextAutoSyncAt = null;
    nextAutoReason = null;
    // Off until the user ticks "Synchro automatique" on the Synchro page
    if (intervalMinutes === 0 || !(await getPrefs()).autoSync) return;

    const now = new Date();
    if (runsLeft(await getCoverage()) > 0) {
        nextAutoSyncAt = new Date(now.getTime() + Math.min(catchUpIntervalMinutes, intervalMinutes) * 60_000);
        nextAutoReason = "catch-up";
    } else {
        const next = new Date(now.getTime() + intervalMinutes * 60_000);
        if (!windows.length || inWindow(windows, next)) {
            nextAutoSyncAt = next;
            nextAutoReason = "window";
        } else {
            nextAutoSyncAt = nextWindowStart(windows, now);
            nextAutoReason = "next-window";
        }
    }
}

function tick(): void {
    if (!nextAutoSyncAt || current || Date.now() < nextAutoSyncAt.getTime()) return;
    const { windows } = config.sync;
    // Woken up after a whole window was missed: wait for the next one instead of syncing off-hours
    if (nextAutoReason !== "catch-up" && windows.length && !inWindow(windows)) {
        void scheduleNext();
        return;
    }
    nextAutoSyncAt = null;
    void startSync("auto", "full");
}

export function getSchedule() {
    return {
        windows: formatWindows(config.sync.windows),
        intervalMinutes: config.sync.intervalMinutes,
        nextAutoSyncAt,
        reason: nextAutoReason,
    };
}

/** Starts a sync in the background. Returns null if one is already running. */
export async function startSync(trigger: "manual" | "auto", mode: SyncMode = "full"): Promise<SyncRun | null> {
    if (current) return null;
    const run = await SyncRun.create({ trigger, mode, status: "running", startedAt: new Date(), finishedAt: null, steps: initialSteps(), error: null });
    current = run;

    void runSync(run)
        .catch((err) => {
            run.status = "error";
            run.error = errorMessage(err);
        })
        .finally(async () => {
            run.finishedAt = new Date();
            await run.save().catch((err) => console.error("Cannot save sync run", err));
            current = null;
            // New activities or interval titles: the kind of session may have changed
            await refreshCategories().catch((err) => console.error("Cannot refresh categories", err));
            invalidateLoadCache();
            console.log(`[sync] #${run.id} ${run.status} in ${run.finishedAt.getTime() - run.startedAt.getTime()} ms`);
            await scheduleNext().catch((err) => console.error("Cannot schedule next sync", err));
        });

    return run;
}

/** Empties every synced table. Garmin tokens are kept, so the next sync starts right away. */
export async function resetData(): Promise<boolean> {
    if (current) return false;
    await sequelize.transaction(async (transaction) => {
        for (const model of [ActivityDetail, Activity, DailyMetric, FitnessSnapshot, SyncRun]) {
            await (model as unknown as typeof Setting).destroy({ where: {}, transaction });
        }
        // Keep the migration markers, or the next restart would redo them on fresh data
        await Setting.destroy({
            where: { [Op.and]: [{ key: { [Op.notIn]: ["detailsVersion", "intervalsVersion", "trimpVersion"] } }, { key: { [Op.notLike]: `${PREF_PREFIX}%` } }] },
            transaction,
        });
    });
    await sequelize.query("VACUUM");
    return true;
}

export function getCurrentRun(): SyncRun | null {
    return current;
}

export function getNextAutoSyncAt(): Date | null {
    return nextAutoSyncAt;
}

/** Called when the "Synchro automatique" box changes. */
export async function rescheduleAutoSync(): Promise<void> {
    await scheduleNext();
}

export async function scheduleAutoSync(): Promise<void> {
    await scheduleNext();
    clearInterval(ticker);
    ticker = setInterval(tick, 30_000);
}
