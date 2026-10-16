import { Router } from "express";
import { Op } from "sequelize";
import { config, garminConfigured } from "./config.js";
import { Activity, ActivityDetail, DailyMetric, FitnessSnapshot, SyncRun } from "./db.js";
import { toDateKey } from "./garmin.js";
import { analyzeIntervals } from "./intervals.js";
import { loadHistory, loadSeries, withEffort } from "./load.js";
import { briefing, loadComparison } from "./briefing.js";
import { healthSummary } from "./health-summary.js";
import { reviewList, setManualCategory } from "./review.js";
import { CATEGORIES, type Category } from "./categories.js";
import { analyzeSession } from "./session-analysis.js";
import { getPrefs, setPrefs, type Prefs } from "./prefs.js";
import { getCatchUp, getCurrentRun, getNextAutoSyncAt, getSchedule, rescheduleAutoSync, resetData, startSync } from "./sync.js";

export const router = Router();

function clampInt(raw: unknown, fallback: number, max: number): number {
    const value = Number.parseInt(String(raw ?? ""), 10);
    if (Number.isNaN(value) || value < 0) return fallback;
    return Math.min(value, max);
}

/** Monday of the week containing `date`, as YYYY-MM-DD. */
function weekStart(date: Date): string {
    const d = new Date(date);
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
    return toDateKey(d);
}

router.get("/sync/status", async (_req, res) => {
    const lastFinished = await SyncRun.findOne({
        where: { finishedAt: { [Op.ne]: null } },
        order: [["startedAt", "DESC"]],
    });
    const lastSuccess = await SyncRun.findOne({
        where: { status: { [Op.in]: ["success", "partial"] } },
        order: [["startedAt", "DESC"]],
    });
    res.json({
        configured: garminConfigured,
        mock: config.garmin.mock,
        current: getCurrentRun(),
        lastRun: lastFinished,
        lastSuccessAt: lastSuccess?.finishedAt ?? null,
        nextAutoSyncAt: getNextAutoSyncAt(),
        schedule: getSchedule(),
        prefs: await getPrefs(),
        intervalMinutes: config.sync.intervalMinutes,
        scope: {
            activitiesLimit: config.sync.activitiesLimit,
            wellnessDays: config.sync.wellnessDays,
            detailsPerRun: config.sync.detailsPerRun,
            wellnessBackfillPerRun: config.sync.wellnessBackfillPerRun,
        },
        catchUp: await getCatchUp(),
        catchUpIntervalMinutes: config.sync.catchUpIntervalMinutes,
    });
});

router.post("/sync", async (req, res) => {
    if (!garminConfigured) {
        res.status(400).json({ error: "Identifiants Garmin manquants (GARMIN_USERNAME / GARMIN_PASSWORD)." });
        return;
    }
    // Manual syncs default to "today": latest data first, in a few seconds
    const run = await startSync("manual", req.body?.mode === "full" ? "full" : "today");
    if (!run) {
        res.status(409).json({ error: "Une synchronisation est déjà en cours." });
        return;
    }
    res.status(202).json(run);
});

router.get("/sync/history", async (req, res) => {
    const runs = await SyncRun.findAll({ order: [["startedAt", "DESC"]], limit: clampInt(req.query.limit, 20, 100) });
    res.json(runs);
});

router.get("/activities", async (req, res) => {
    const key = String(req.query.category ?? "all");
    const where = CATEGORIES.includes(key as Category) ? { category: key } : {};
    // Counts per kind of session, for the filter menu: only with the first page
    let counts: Record<string, number> | undefined;
    if (clampInt(req.query.offset, 0, Number.MAX_SAFE_INTEGER) === 0) {
        const groups = (await Activity.count({ group: ["category"] })) as unknown as { category: string | null; count: number }[];
        counts = { ...Object.fromEntries(groups.filter((g) => g.category).map((g) => [g.category, g.count])), all: groups.reduce((t, g) => t + g.count, 0) };
    }
    const { rows, count } = await Activity.findAndCountAll({
        where,
        order: [["startTimeLocal", "DESC"]],
        limit: clampInt(req.query.limit, 20, 100),
        offset: clampInt(req.query.offset, 0, Number.MAX_SAFE_INTEGER),
    });
    res.json({ items: await withEffort(rows), total: count, counts });
});

router.get("/activities/:id", async (req, res) => {
    const activity = await Activity.findByPk(req.params.id);
    if (!activity) {
        res.status(404).json({ error: "Activité introuvable." });
        return;
    }
    const detail = await ActivityDetail.findByPk(req.params.id);
    const intervals = detail && activity.type.includes("running") ? analyzeIntervals(detail.laps) : null;
    const [withScore] = await withEffort([activity]);
    const analysis = intervals && detail ? await analyzeSession(activity.id, detail.samples, detail.laps) : null;
    res.json({ ...withScore, detail, intervals, analysis });
});

/** Kind chosen by hand. Body: { category: "competition" | "interval" | … } or { category: null } to go back to the detection. */
router.put("/activities/:id/category", async (req, res) => {
    const value = req.body?.category ?? null;
    if (value !== null && !CATEGORIES.includes(value)) {
        res.status(400).json({ error: `category doit être null ou l'un de : ${CATEGORIES.join(", ")}` });
        return;
    }
    const category = await setManualCategory(req.params.id, value);
    if (!category) {
        res.status(404).json({ error: "Activité introuvable." });
        return;
    }
    res.json({ category, manual: value !== null });
});

/** Runs that look like a race and have not been classified by hand, with the reasons. */
router.get("/review", async (_req, res) => {
    res.json(await reviewList());
});

router.get("/daily", async (req, res) => {
    const days = clampInt(req.query.days, 14, 365);
    const rows = await DailyMetric.findAll({ order: [["date", "DESC"]], limit: days });
    res.json(rows.reverse());
});

router.get("/summary", async (req, res) => {
    const weeks = Math.max(1, clampInt(req.query.weeks, 8, 52));
    const from = new Date();
    from.setDate(from.getDate() - weeks * 7);
    const activities = await Activity.findAll({
        where: { startTimeLocal: { [Op.gte]: weekStart(from) } },
        attributes: ["startTimeLocal", "distance", "duration", "type", "elevationGain"],
    });
    // ?sport=running: only runs, for the weekly running chart
    const kept = req.query.sport === "running" ? activities.filter((a) => a.type.includes("running")) : activities;

    const byWeek = new Map<string, { week: string; distance: number; duration: number; elevation: number; count: number }>();
    for (let i = weeks - 1; i >= 0; i--) {
        const d = new Date();
        d.setDate(d.getDate() - i * 7);
        const key = weekStart(d);
        byWeek.set(key, { week: key, distance: 0, duration: 0, elevation: 0, count: 0 });
    }
    for (const a of kept) {
        const bucket = byWeek.get(weekStart(new Date(a.startTimeLocal.replace(" ", "T"))));
        if (!bucket) continue;
        bucket.distance += a.distance ?? 0;
        bucket.duration += a.duration ?? 0;
        bucket.elevation += a.elevationGain ?? 0;
        bucket.count += 1;
    }

    const lastRun = await Activity.findOne({
        where: { type: { [Op.like]: "%running%" } },
        order: [["startTimeLocal", "DESC"]],
    });
    res.json({ weeks: [...byWeek.values()], lastRun: lastRun ? (await withEffort([lastRun]))[0] : null });
});

router.get("/fitness", async (req, res) => {
    const history = await FitnessSnapshot.findAll({ order: [["date", "DESC"]], limit: clampInt(req.query.days, 90, 365) });
    res.json({ latest: history[0] ?? null, history: history.reverse() });
});

/** Wipes the local copy of the data, then starts a fresh sync (Garmin tokens are kept). */
router.delete("/data", async (_req, res) => {
    if (!(await resetData())) {
        res.status(409).json({ error: "Une synchronisation est en cours, réessaie quand elle est finie." });
        return;
    }
    const run = garminConfigured ? await startSync("manual", "full") : null;
    res.json({ ok: true, run });
});

/** Effort / forme / fatigue, see load.ts for the scales. */
router.get("/load", async (req, res) => {
    res.json(await loadSeries(clampInt(req.query.days, 90, 3650)));
});

/** Health page: five aggregated indicators with their trend over `days`. */
router.get("/health/summary", async (req, res) => {
    res.json(await healthSummary(Math.max(7, clampInt(req.query.days, 30, 365))));
});

/** Home page: first name, text of the day, km / load / readiness cards. */
router.get("/briefing", async (_req, res) => {
    res.json(await briefing());
});

/** Last 7 days versus the three weeks before, session by session: what makes the load go up or down. */
router.get("/load/compare", async (_req, res) => {
    res.json(await loadComparison());
});

/** Whole history, one point per month: average 4-week load and best VO2 max. */
router.get("/load/history", async (_req, res) => {
    res.json(await loadHistory());
});

/** Saves the automation checkbox. Body: { autoSync?: boolean } */
router.put("/preferences", async (req, res) => {
    const patch: Partial<Prefs> = {};
    for (const key of ["autoSync"] as const) {
        const value = req.body?.[key];
        if (value === undefined) continue;
        if (typeof value !== "boolean") {
            res.status(400).json({ error: `${key} doit être true ou false` });
            return;
        }
        patch[key] = value;
    }
    const prefs = await setPrefs(patch);
    if (patch.autoSync !== undefined) await rescheduleAutoSync();
    res.json(prefs);
});
