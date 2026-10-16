import type { Coverage, StepKey, SyncRun, SyncStatus } from "./api";

// Rough share of a sync's duration taken by each step
const STEP_WEIGHTS: Record<StepKey, number> = { auth: 1, activities: 2, details: 4, wellness: 4, fitness: 1 };

/** Overall progress of a running sync, from its steps. Unknown keys (older runs) are ignored. */
export function runProgress(run: SyncRun): number {
    let done = 0;
    let total = 0;
    for (const step of run.steps) {
        const w = STEP_WEIGHTS[step.key] ?? 0;
        total += w;
        if (step.status === "success" || step.status === "error" || step.status === "skipped") done += w;
        else if (step.status === "running" && step.total) done += w * Math.min(step.count / step.total, 1);
    }
    return total ? Math.round((done / total) * 100) : 0;
}

export const ratio = (c: Coverage) => (c.total ? Math.min(c.done / c.total, 1) : 1);

/** Share of the Garmin history stored locally, all data types averaged. */
export function overallCoverage(coverage: SyncStatus["catchUp"]["coverage"]): number {
    const parts = [coverage.activities, coverage.details, coverage.wellness];
    return Math.round((parts.reduce((sum, c) => sum + ratio(c), 0) / parts.length) * 100);
}

/** "1 h 05", "12 min", "40 s" */
export function formatEta(seconds: number): string {
    if (seconds < 60) return `${Math.max(Math.round(seconds), 1)} s`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) return `${minutes} min`;
    return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, "0")}`;
}

/** Remaining time of the running sync, extrapolated from elapsed time and progress. */
export function runEta(run: SyncRun, progress: number): number | null {
    if (progress < 5) return null;
    const elapsed = (Date.now() - new Date(run.startedAt).getTime()) / 1000;
    return (elapsed * (100 - progress)) / progress;
}
