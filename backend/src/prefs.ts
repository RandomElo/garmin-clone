import { getSetting, setSetting } from "./db.js";

/**
 * User choices saved in the database (they survive restarts and "reset data").
 * Off by default: nothing runs on its own until the user ticks the box.
 */
export interface Prefs {
    /** Scheduled syncs (time windows and history catch-up) */
    autoSync: boolean;
}

export const PREF_PREFIX = "pref.";
const keys: Record<keyof Prefs, string> = { autoSync: `${PREF_PREFIX}autoSync` };

export async function getPrefs(): Promise<Prefs> {
    return {
        autoSync: (await getSetting(keys.autoSync)) === "true",
    };
}

export async function setPrefs(patch: Partial<Prefs>): Promise<Prefs> {
    for (const [name, value] of Object.entries(patch) as [keyof Prefs, boolean][]) {
        await setSetting(keys[name], String(value));
    }
    return getPrefs();
}
