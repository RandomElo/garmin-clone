import { Op } from "sequelize";
import { Activity } from "./db.js";
import { categoryOf, detectedCategory, type Category } from "./categories.js";
import { hrRefs, invalidateLoadCache } from "./load.js";

/**
 * "À classer": runs that look like a race, found by plain rules (no guess hidden from the user):
 * each criterion met adds points and is shown as a reason. A run is listed from REVIEW_SCORE points
 * until a kind is chosen for it by hand.
 */

const RACE_DISTANCES = [
    { meters: 5000, label: "5 km" },
    { meters: 10_000, label: "10 km" },
    { meters: 15_000, label: "15 km" },
    { meters: 21_097, label: "semi-marathon" },
    { meters: 42_195, label: "marathon" },
];
const DISTANCE_TOLERANCE = 0.03;
// Average HR of a race, as a share of max HR: lower over a marathon than over a 5 km
const raceHrShare = (meters: number) => (meters >= 35_000 ? 0.8 : meters >= 18_000 ? 0.86 : 0.88);
const RACE_WORDS = /\b(semi|marathon|trail|cross|corrida|foul[ée]es?|course de|10 ?km|5 ?km|race|comp[ée]t\w*|chrono|run \d+)\b/i;
const REVIEW_SCORE = 2.5;

export interface ReviewItem {
    id: number;
    name: string;
    startTimeLocal: string;
    distance: number | null;
    duration: number | null;
    averageSpeed: number | null;
    averageHr: number | null;
    detected: Category;
    score: number;
    reasons: string[];
}

export async function reviewList(): Promise<ReviewItem[]> {
    const runs = await Activity.findAll({
        where: { type: { [Op.like]: "%running%" } },
        attributes: ["id", "name", "type", "startTimeLocal", "distance", "duration", "averageSpeed", "averageHr", "aerobicTe", "intervalTitle", "manualCategory"],
        order: [["startTimeLocal", "DESC"]],
    });
    const { max } = await hrRefs();
    const items: ReviewItem[] = [];

    for (const a of runs) {
        if (a.manualCategory || !a.distance || !a.averageSpeed) continue;
        const d = a.distance;
        const reasons: string[] = [];
        let score = 0;

        const official = RACE_DISTANCES.find((r) => Math.abs(d - r.meters) / r.meters <= DISTANCE_TOLERANCE);
        if (official) {
            score += 1;
            reasons.push(`Distance de course : ${official.label}`);
        }

        if (a.averageHr && a.averageHr >= max * raceHrShare(d)) {
            score += 1;
            reasons.push(`FC moyenne ${Math.round((a.averageHr / max) * 100)} % de ta FC max`);
        }

        // Faster than nearly all the runs of a similar distance (±20 %) of the two years before
        const from = new Date(`${a.startTimeLocal.slice(0, 10)}T12:00:00`);
        from.setFullYear(from.getFullYear() - 2);
        const similar = runs.filter((r) => r !== a && r.distance && r.averageSpeed && Math.abs(r.distance - d) / d <= 0.2 && r.startTimeLocal < a.startTimeLocal && r.startTimeLocal >= from.toISOString().slice(0, 10));
        if (similar.length >= 10) {
            const slower = similar.filter((r) => r.averageSpeed! < a.averageSpeed!).length / similar.length;
            if (slower >= 0.95) {
                score += 1.5;
                reasons.push(`Plus rapide que ${Math.round(slower * 100)} % de tes sorties de cette distance`);
            }
        }

        // Garmin's default names ("Paris Course à pied") say nothing: only words typical of a race count
        const name = a.name.replace(/course à pied/i, "");
        if (RACE_WORDS.test(name)) {
            score += 1.5;
            reasons.push(`Nom : « ${a.name} »`);
        }

        if (!a.intervalTitle && (a.aerobicTe ?? 0) >= 4.2) {
            score += 0.5;
            reasons.push(`Training Effect aérobie ${a.aerobicTe!.toLocaleString("fr-FR", { maximumFractionDigits: 1 })}`);
        }

        const start = new Date(a.startTimeLocal.replace(" ", "T"));
        const hour = start.getHours() + start.getMinutes() / 60;
        if ((start.getDay() === 0 || start.getDay() === 6) && hour >= 7 && hour <= 11.5) {
            score += 0.5;
            reasons.push("Départ le week-end en matinée");
        }

        if (score >= REVIEW_SCORE) {
            items.push({
                id: a.id,
                name: a.name,
                startTimeLocal: a.startTimeLocal,
                distance: a.distance,
                duration: a.duration,
                averageSpeed: a.averageSpeed,
                averageHr: a.averageHr,
                detected: detectedCategory(a),
                score,
                reasons,
            });
        }
    }
    return items;
}

/** Kind chosen by hand (null = back to the detection). Takes effect at once in lists and load. */
export async function setManualCategory(id: string, category: Category | null): Promise<Category | null> {
    const a = await Activity.findByPk(id);
    if (!a) return null;
    a.manualCategory = category;
    a.category = categoryOf(a);
    await a.save();
    invalidateLoadCache();
    return a.category as Category;
}
