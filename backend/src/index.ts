import express, { type NextFunction, type Request, type Response } from "express";
import { config, garminConfigured } from "./config.js";
import { SyncRun, initDb } from "./db.js";
import { router } from "./routes.js";
import { refreshCategories } from "./categories.js";
import { refreshTrimps } from "./load.js";
import { getPrefs } from "./prefs.js";
import { refreshIntervalTitles, scheduleAutoSync, startSync } from "./sync.js";

const app = express();
app.disable("x-powered-by");
app.use(express.json());

app.get("/api/health", (_req, res) => {
    res.json({ ok: true });
});
app.use("/api", router);

app.use((err: Error, _req: Request, res: Response, _next: NextFunction) => {
    console.error(err);
    res.status(500).json({ error: "Erreur interne." });
});

await initDb();
await refreshIntervalTitles();
await refreshTrimps();
await refreshCategories();

app.listen(config.port, () => {
    console.log(`API listening on http://localhost:${config.port}${config.garmin.mock ? " (mock Garmin data)" : ""}`);
});

if (garminConfigured) {
    await scheduleAutoSync();
    // First launch: fill the database without waiting for the first interval
    if ((await SyncRun.count()) === 0 && (await getPrefs()).autoSync) void startSync("auto", "full");
} else {
    console.warn("GARMIN_USERNAME / GARMIN_PASSWORD not set: sync disabled (set GARMIN_MOCK=true to try with fake data)");
}
