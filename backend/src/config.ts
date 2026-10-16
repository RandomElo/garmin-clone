import path from "node:path";
import { parseWindows } from "./schedule.js";

function intFromEnv(name: string, fallback: number): number {
    const raw = process.env[name];
    if (!raw) return fallback;
    const value = Number.parseInt(raw, 10);
    if (Number.isNaN(value) || value < 0) {
        throw new Error(`${name} must be a positive integer (got "${raw}")`);
    }
    return value;
}

const dataDir = path.resolve(process.env.DATA_DIR ?? "./data");

export const config = {
    port: intFromEnv("PORT", 3001),
    dataDir,
    dbPath: path.join(dataDir, "garmin.sqlite"),
    tokenDir: path.join(dataDir, "tokens"),
    garmin: {
        username: process.env.GARMIN_USERNAME ?? "",
        password: process.env.GARMIN_PASSWORD ?? "",
        domain: process.env.GARMIN_DOMAIN === "garmin.cn" ? ("garmin.cn" as const) : ("garmin.com" as const),
        // Fake data generator, to try the app without a Garmin account
        mock: process.env.GARMIN_MOCK === "true",
    },
    sync: {
        // Automatic syncs only run inside these windows (local time), every intervalMinutes. Empty = all day.
        windows: parseWindows(process.env.SYNC_WINDOWS ?? "10:00-12:00,18:00-22:00"),
        // 0 disables automatic sync (manual button only)
        intervalMinutes: intFromEnv("SYNC_INTERVAL_MINUTES", 10),
        // Activity history to keep in sync (0 = the whole Garmin history)
        activitiesLimit: intFromEnv("SYNC_ACTIVITIES_LIMIT", 0),
        // Wellness window, in days back from today
        wellnessDays: intFromEnv("SYNC_WELLNESS_DAYS", 365),
        // Last days always re-fetched: their values keep changing for a while
        wellnessRecentDays: intFromEnv("SYNC_WELLNESS_RECENT_DAYS", 3),
        // History is caught up in batches, so a single sync stays short and Garmin does not throttle us
        wellnessBackfillPerRun: intFromEnv("SYNC_WELLNESS_BACKFILL_PER_RUN", 30),
        detailsPerRun: intFromEnv("SYNC_DETAILS_PER_RUN", 60),
        // While the history is incomplete, syncs follow each other at this pace instead of the normal interval
        catchUpIntervalMinutes: intFromEnv("SYNC_CATCHUP_INTERVAL_MINUTES", 2),
        // Pause between Garmin calls, to stay polite with their API
        requestDelayMs: intFromEnv("SYNC_REQUEST_DELAY_MS", 300),
    },
};

export const garminConfigured = config.garmin.mock || Boolean(config.garmin.username && config.garmin.password);
