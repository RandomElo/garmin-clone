import { Op } from "sequelize";
import { categoryOf as kindOf, type Category as SessionKind } from "./categories.js";
import { Activity, getSetting } from "./db.js";
import { toDateKey } from "./garmin.js";
import { effortOf, loadOf, loadOnDay, loadPoints } from "./load.js";
import { latestReadiness, type Factor, type Readiness } from "./readiness.js";
import { latestSessionAnalysis } from "./session-analysis.js";
import { addDays, capitalize, dec, join, pick, plural, seedOf, when } from "./text.js";

/**
 * Home page: first name, a short text about the day, and three cards (km, load, readiness).
 * The load card explains itself: the last 7 days are compared session by session with the three
 * weeks before, grouped by kind of session, so "your load is down" comes with the reason.
 */

/** The shared kinds of session, with the footings split into easy and steady (tempo) for the load */
export type Category = SessionKind | "tempo";

const categoryLabels: Record<Category, { one: string; many: string }> = {
    competition: { one: "compétition", many: "compétitions" },
    interval: { one: "séance de fractionné", many: "séances de fractionné" },
    long: { one: "sortie longue", many: "sorties longues" },
    tempo: { one: "séance soutenue", many: "séances soutenues" },
    easy: { one: "footing", many: "footings" },
    strength: { one: "séance de musculation", many: "séances de musculation" },
    other: { one: "autre sport", many: "autres sports" },
};

export interface WindowSession {
    id: number;
    startTimeLocal: string;
    title: string;
    category: Category;
    km: number;
    /** Share of the 7-day load scale (on 20) */
    points: number;
}

export interface LoadWindow {
    from: string;
    to: string;
    points: number;
    km: number;
    sessions: WindowSession[];
}

export interface CategoryDiff {
    category: Category;
    label: string;
    count: number;
    avgCount: number;
    points: number;
    avgPoints: number;
    delta: number;
}

export interface LoadComparison {
    current: LoadWindow;
    previous: LoadWindow[];
    avgPoints: number;
    avgKm: number;
    delta: number;
    categories: CategoryDiff[];
    explanation: string[];
}

function categoryOf(a: Activity): Category {
    const kind = (a.category as SessionKind | null) ?? kindOf(a);
    return kind === "easy" && (a.aerobicTe ?? 0) >= 3.5 ? "tempo" : kind;
}

const round1 = (v: number) => Math.round(v * 10) / 10;

export async function loadComparison(today = toDateKey(new Date())): Promise<LoadComparison> {
    const start = addDays(today, -27);
    const activities = await Activity.findAll({ where: { startTimeLocal: { [Op.gte]: start } }, order: [["startTimeLocal", "ASC"]] });
    const sessions = await Promise.all(
        activities.map(async (a): Promise<WindowSession> => ({
            id: a.id,
            startTimeLocal: a.startTimeLocal,
            title: a.intervalTitle ?? a.name,
            category: categoryOf(a),
            km: a.type.includes("running") ? round1((a.distance ?? 0) / 1000) : 0,
            points: await loadPoints((await loadOf(a)) ?? 0),
        })),
    );

    const windowOf = (k: number): LoadWindow => {
        const to = addDays(today, -7 * k);
        const from = addDays(to, -6);
        const list = sessions.filter((s) => s.startTimeLocal.slice(0, 10) >= from && s.startTimeLocal.slice(0, 10) <= to);
        return { from, to, points: round1(list.reduce((t, s) => t + s.points, 0)), km: Math.round(list.reduce((t, s) => t + s.km, 0)), sessions: list };
    };
    const current = windowOf(0);
    const previous = [1, 2, 3].map(windowOf);
    const avgPoints = round1(previous.reduce((t, w) => t + w.points, 0) / 3);
    const avgKm = Math.round(previous.reduce((t, w) => t + w.km, 0) / 3);

    const categories = (Object.keys(categoryLabels) as Category[])
        .map((category): CategoryDiff => {
            const mine = current.sessions.filter((s) => s.category === category);
            const before = previous.flatMap((w) => w.sessions.filter((s) => s.category === category));
            const points = round1(mine.reduce((t, s) => t + s.points, 0));
            const avg = round1(before.reduce((t, s) => t + s.points, 0) / 3);
            return { category, label: capitalize(categoryLabels[category].many), count: mine.length, avgCount: round1(before.length / 3), points, avgPoints: avg, delta: round1(points - avg) };
        })
        .filter((c) => c.count || c.avgCount)
        .sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta));

    const delta = round1(current.points - avgPoints);
    return { current, previous, avgPoints, avgKm, delta, categories, explanation: explain(current, avgPoints, delta, categories) };
}

const nouns: Record<Category, string> = { competition: "compétitions", interval: "fractionné", long: "sorties longues", tempo: "séances soutenues", easy: "footings", strength: "musculation", other: "autres sports" };

/** One plain sentence: is the load up or down, and which kind of session explains it. */
function explain(current: LoadWindow, avgPoints: number, delta: number, categories: CategoryDiff[]): string[] {
    if (Math.abs(delta) < 0.8) return [`Charge stable : ${dec(current.points)} cette semaine glissante, contre ${dec(avgPoints)} en moyenne les trois semaines d'avant.`];
    const up = delta > 0;
    const reasons = categories
        .filter((c) => Math.abs(c.delta) >= 0.6 && Math.sign(c.delta) === Math.sign(delta))
        .slice(0, 2)
        .map((c) => `${c.delta > 0 ? "plus" : "moins"} de ${nouns[c.category]} (${c.count} contre ${dec(c.avgCount)} par semaine avant)`);
    const head = `Charge en ${up ? "hausse" : "baisse"} de ${dec(Math.abs(delta))} point${Math.abs(delta) >= 2 ? "s" : ""}`;
    // The biggest move in the other direction, so the table never contradicts the sentence silently
    const against = categories.find((c) => Math.abs(c.delta) >= 1 && Math.sign(c.delta) !== Math.sign(delta));
    const counter = against ? `, même si tu as fait ${against.delta > 0 ? "plus" : "moins"} de ${nouns[against.category]} (${against.count} contre ${dec(against.avgCount)})` : "";
    return [reasons.length ? `${head}, surtout parce que tu as fait ${join(reasons)}${counter}.` : `${head}, sans type de séance qui ressorte : c'est l'ensemble qui est ${up ? "plus" : "moins"} chargé${counter}.`];
}

/* ---------- Home ---------- */

// How much each factor is worth mentioning: HRV and sleep are the best studied signals, Body Battery derives from them
const salience: Record<Factor["key"], number> = { hrv: 1, sleep: 1, restingHr: 0.9, sleepScore: 0.8, fatigue: 0.8, stress: 0.5, bodyBattery: 0.45 };

function factorBit(f: Factor): string {
    switch (f.key) {
        case "sleep":
            return `nuit de ${f.display} (${f.note})`;
        case "sleepScore":
            return `score de sommeil à ${f.display}`;
        case "hrv":
            return `VFC à ${f.display}, ${f.note}`;
        case "restingHr":
            return `FC au repos à ${f.display}`;
        case "bodyBattery":
            return `Body Battery à ${f.display}`;
        case "stress":
            return `stress à ${f.display}`;
        case "fatigue":
            return `fatigue d'entraînement à ${f.display}`;
    }
}

/** Describes the day with the one or two factors that stand out the most, not always the same one. */
function readinessSentence(r: Readiness, seed: number): string {
    const ranked = [...r.factors].filter((f) => f.baseline !== null || f.key === "fatigue").sort((a, b) => Math.abs(b.z) * salience[b.key] - Math.abs(a.z) * salience[a.key]);
    const notable = ranked.filter((f) => Math.abs(f.z) >= 0.5).slice(0, 2);
    if (r.score >= 66) {
        const goods = notable.filter((f) => f.z > 0);
        const shown = goods.length ? goods : r.factors.filter((f) => f.key === "sleep" || f.key === "hrv");
        return pick(seed, [`Bien récupéré : ${join(shown.map(factorBit))}.`, `Les voyants sont au vert : ${join(shown.map(factorBit))}.`, `${capitalize(join(shown.map(factorBit)))} : le corps suit.`]);
    }
    const lows = notable.filter((f) => f.z < 0);
    if (r.score >= 52) return lows.length ? `Récup correcte, sans plus : ${join(lows.map(factorBit))}.` : "Récup dans ta moyenne, rien qui sorte du lot cette nuit.";
    return pick(seed, [`Le corps tire un peu : ${join(lows.map(factorBit))}.`, `Petit signal de fatigue : ${join(lows.map(factorBit))}.`]);
}

interface Candidate {
    weight: number;
    text: string;
}

/** Monday of the week containing `date`, as YYYY-MM-DD. */
function mondayOf(date: string): string {
    const d = new Date(`${date}T12:00:00`);
    return addDays(date, -((d.getDay() + 6) % 7));
}

async function runKm(from: string, to: string): Promise<{ km: number; sessions: number }> {
    const runs = await Activity.findAll({ where: { startTimeLocal: { [Op.gte]: from, [Op.lt]: `${addDays(to, 1)}` }, type: { [Op.like]: "%running%" } }, attributes: ["distance"] });
    return { km: Math.round(runs.reduce((t, a) => t + (a.distance ?? 0), 0) / 100) / 10, sessions: runs.length };
}

/** Days since the last hard session (effort ≥ 6 / 10), null if none in the last 3 weeks. */
async function daysSinceHard(today: string): Promise<number | null> {
    const recent = await Activity.findAll({ where: { startTimeLocal: { [Op.gte]: addDays(today, -21) } }, attributes: ["startTimeLocal", "trainingLoad", "trimp"], order: [["startTimeLocal", "DESC"]] });
    for (const a of recent) {
        if ((effortOf(await loadOf(a)) ?? 0) >= 6) return Math.round((Date.parse(`${today}T12:00:00`) - Date.parse(`${a.startTimeLocal.slice(0, 10)}T12:00:00`)) / 86_400_000);
    }
    return null;
}

export async function briefing() {
    const today = toDateKey(new Date());
    const seed = seedOf(today);
    const monday = mondayOf(today);
    const [readiness, comparison, last, name, loadToday, week, lastWeek, sinceHard, photo] = await Promise.all([
        latestReadiness(),
        loadComparison(today),
        latestSessionAnalysis(),
        getSetting("profileName"),
        loadOnDay(today),
        runKm(monday, today),
        runKm(addDays(monday, -7), addDays(monday, -1)),
        daysSinceHard(today),
        getSetting("garminPhoto"),
    ]);

    // Several possible topics; the text keeps the readiness and the most relevant other one (with a little daily variety)
    const candidates: Candidate[] = [];
    const jitter = (k: number) => (Math.abs(Math.sin(seed * 7 + k * 13)) - 0.5) * 0.4;

    const lastDate = last?.startTimeLocal.slice(0, 10);
    if (last && lastDate && lastDate >= addDays(today, -4)) {
        const v = last.analysis;
        const title = last.title.replace(/\s*\(r\u00a0:[^)]*\)/g, "").replaceAll("\u00a0", " ");
        const gap = v.paceDeltaSecPerKm;
        const verdict =
            v.verdict === "first"
                ? "nouvelle référence posée"
                : v.verdict === "excellent" || v.verdict === "good"
                  ? gap !== null && gap < 0
                      ? `${-gap} s/km plus vite que d'habitude`
                      : "réussi"
                  : v.verdict === "standard"
                    ? "dans tes standards"
                    : v.verdict === "controlled"
                      ? "séance gérée, FC plus basse que d'habitude"
                      : gap
                        ? `${gap} s/km plus lent que d'habitude`
                        : "un ton en dessous";
        const strong = v.verdict === "excellent" || v.verdict === "hard" || v.verdict === "below";
        candidates.push({ weight: 0.8 + (strong ? 0.4 : 0) + jitter(1), text: pick(seed + 1, [`Ton ${title} ${when(lastDate, today)} : ${verdict}.`, `Dernier fractionné (${title}, ${when(lastDate, today)}) : ${verdict}.`]) });
    }

    if (sinceHard !== null && sinceHard <= 1) {
        candidates.push({ weight: 1 + jitter(2), text: sinceHard === 0 ? "Séance dure aujourd'hui : place à la récup ce soir." : "Séance dure hier : aujourd'hui, du facile ou du repos." });
    } else if (sinceHard !== null && sinceHard >= 4 && (readiness?.score ?? 0) >= 60) {
        candidates.push({ weight: 0.75 + jitter(3), text: pick(seed + 3, [`Ta dernière séance dure date de ${sinceHard} jours : la place est libre pour du qualitatif.`, `${sinceHard} jours sans séance dure, c'est un bon créneau pour en placer une.`]) });
    }

    if ((loadToday?.fatigue ?? 0) > 7) {
        candidates.push({ weight: 1.1 + jitter(4), text: `Ta semaine est nettement plus chargée que ton mois (fatigue ${dec(loadToday!.fatigue!)}/10) : garde les prochains jours tranquilles.` });
    }

    if (lastWeek.km > 0 && week.km > lastWeek.km) {
        candidates.push({ weight: 0.6 + jitter(5), text: `Déjà ${week.km} km depuis lundi, plus que toute la semaine dernière (${lastWeek.km} km).` });
    }

    // The factor that matters the most in this athlete's own history, when it is notable today
    const link = last?.analysis.correlations.find((c) => c.strength !== "aucune" && readiness?.factors.some((f) => f.key === c.key && Math.abs(f.z) >= 0.5));
    const linkFactor = link && readiness?.factors.find((f) => f.key === link.key);
    if (link && linkFactor) {
        candidates.push({
            weight: 0.7 + jitter(6),
            text: `Chez toi, ${link.label.toLowerCase()} pèse sur tes fractionnés, et il est ${linkFactor.z > 0 ? "bon" : "en retrait"} aujourd'hui (${linkFactor.display}, ${linkFactor.note}).`,
        });
    }

    const sentences: string[] = [];
    if (readiness) sentences.push(readinessSentence(readiness, seed));
    const best = candidates.sort((a, b) => b.weight - a.weight)[0];
    if (best) sentences.push(best.text);

    return {
        name,
        photo,
        headline: sentences.join(" "),
        readiness,
        km: { value: week.km, lastWeek: lastWeek.km, sessions: week.sessions },
        load: { value: loadToday?.week ?? comparison.current.points, previous: comparison.avgPoints, delta: comparison.delta, fatigue: loadToday?.fatigue ?? null },
        comparison,
        lastSession: last ? { id: last.id, startTimeLocal: last.startTimeLocal, title: last.title, verdict: last.analysis.verdict, verdictLabel: last.analysis.verdictLabel, summary: last.analysis.summary } : null,
    };
}
