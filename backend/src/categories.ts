import { Activity } from "./db.js";

/**
 * Kind of each session, shared by the activity filters and the load comparison.
 * A kind chosen by hand in the app always wins; otherwise:
 * - interval: an interval session detected from the laps
 * - long: a run of 19 km or more
 * - strength: strength training recorded on the watch
 * - easy: any other run
 * - other: any other sport
 * Races are never guessed silently: likely ones are listed on the "À classer" page (see review.ts).
 */

export type Category = "competition" | "interval" | "long" | "strength" | "easy" | "other";

export const CATEGORIES: Category[] = ["competition", "interval", "long", "strength", "easy", "other"];

export const LONG_RUN_METERS = 19_000;

const STRENGTH_GARMIN = ["strength_training", "indoor_cardio", "hiit"];

type Fields = Pick<Activity, "type" | "intervalTitle" | "distance" | "manualCategory">;

/** Kind from the data alone, ignoring a manual choice. */
export function detectedCategory(a: Omit<Fields, "manualCategory">): Category {
    if (STRENGTH_GARMIN.includes(a.type)) return "strength";
    if (!a.type.includes("running")) return "other";
    if (a.intervalTitle) return "interval";
    if ((a.distance ?? 0) >= LONG_RUN_METERS) return "long";
    return "easy";
}

export function categoryOf(a: Fields): Category {
    if (a.manualCategory && CATEGORIES.includes(a.manualCategory as Category)) return a.manualCategory as Category;
    return detectedCategory(a);
}

/** Recomputes the stored category of every activity whose kind changed. Local only, a few queries. */
export async function refreshCategories(): Promise<number> {
    const all = await Activity.findAll({ attributes: ["id", "type", "intervalTitle", "distance", "manualCategory", "category"] });
    const changes = new Map<Category, number[]>();
    for (const a of all) {
        const c = categoryOf(a);
        if (c !== a.category) changes.set(c, [...(changes.get(c) ?? []), a.id]);
    }
    for (const [category, ids] of changes) await Activity.update({ category }, { where: { id: ids } });
    return [...changes.values()].reduce((t, ids) => t + ids.length, 0);
}
