/** Time windows for automatic syncs, e.g. "10:00-12:00,18:00-22:00" (local time of the server). */
export interface SyncWindow {
    start: number; // minutes since midnight
    end: number;
}

export function parseWindows(raw: string): SyncWindow[] {
    if (!raw.trim()) return [];
    return raw.split(",").map((part) => {
        const match = part.trim().match(/^(\d{1,2}):?(\d{2})?\s*-\s*(\d{1,2}):?(\d{2})?$/);
        if (!match) throw new Error(`SYNC_WINDOWS: invalid window "${part}" (expected e.g. 18:00-22:00)`);
        const [, h1, m1 = "0", h2, m2 = "0"] = match;
        const start = Number(h1) * 60 + Number(m1);
        const end = Number(h2) * 60 + Number(m2);
        if (start >= end || end > 24 * 60) throw new Error(`SYNC_WINDOWS: invalid window "${part}"`);
        return { start, end };
    });
}

const minutesOf = (d: Date) => d.getHours() * 60 + d.getMinutes();

export function inWindow(windows: SyncWindow[], date = new Date()): boolean {
    const m = minutesOf(date);
    return windows.some((w) => m >= w.start && m < w.end);
}

/** Start of the next window after `date` (today or tomorrow). */
export function nextWindowStart(windows: SyncWindow[], date = new Date()): Date | null {
    if (!windows.length) return null;
    const m = minutesOf(date);
    const sorted = [...windows].sort((a, b) => a.start - b.start);
    const today = sorted.find((w) => w.start > m);
    const target = new Date(date);
    target.setSeconds(0, 0);
    if (today) {
        target.setHours(0, today.start, 0, 0);
    } else {
        target.setDate(target.getDate() + 1);
        target.setHours(0, sorted[0].start, 0, 0);
    }
    return target;
}

export function formatWindows(windows: SyncWindow[]): string[] {
    const hm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
    return windows.map((w) => `${hm(w.start)}-${hm(w.end)}`);
}
