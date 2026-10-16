import { Op } from "sequelize";
import { Activity } from "./db.js";
import { toDateKey } from "./garmin.js";
import { loadSeries } from "./load.js";
import { dailyMetrics, labelOf, readinessOn } from "./readiness.js";
import { addDays, dec, hours, mean, median, pace, sd, shortDuration } from "./text.js";

/**
 * The health page in five numbers: everything the watch measures, aggregated into indicators
 * that each answer one question.
 * - Récupération: am I recovered today? (readiness, see readiness.ts)
 * - Sommeil: do I sleep enough lately? (last 7 nights versus the 60 before)
 * - VFC: is my nervous system balanced? (7-night average versus the normal range of 60 nights,
 *   the approach of HRV-guided training studies; resting HR trend alongside)
 * - Condition: is my fitness going up? (pace at 150 bpm on steady runs, last 4 weeks versus the 8 before)
 * - Fatigue: is my training load reasonable? (the week versus the month, see load.ts)
 */

export type MetricStatus = "good" | "normal" | "low";

export interface HealthMetric {
    key: "recovery" | "sleep" | "hrv" | "fitness" | "fatigue";
    label: string;
    value: string;
    unit: string | null;
    status: MetricStatus;
    statusLabel: string;
    /** "18 min de moins que d'habitude" */
    delta: string | null;
    sentence: string;
    /** Trend over the chosen period, oldest first */
    series: { date: string; value: number | null }[];
    /** Draw the trend upside down (pace: lower is better), so that up always reads as progress */
    inverted: boolean;
    /** Raw value placed on the scale of the explanation (score, hours, ms, s/km gained, /10) */
    numeric: number;
    /** Personal normal range, for the HRV */
    band?: [number, number];
}

const EFFICIENCY_HR = 150;

// "Sommeil" -> "sommeil" inside a sentence, but brand names keep their capitals
const inSentence = (label: string) => (label.startsWith("Body") ? label : label[0].toLowerCase() + label.slice(1));

function rolling(values: (number | null)[], n: number): (number | null)[] {
    return values.map((_, i) => {
        const window = values.slice(Math.max(0, i - n + 1), i + 1).filter((v): v is number => v !== null);
        return window.length >= Math.min(3, n) ? mean(window) : null;
    });
}

async function recovery(dates: string[]): Promise<HealthMetric | null> {
    const scores = await Promise.all(dates.map(async (d) => ({ date: d, value: (await readinessOn(d))?.score ?? null })));
    const last = [...scores].reverse().find((s) => s.value !== null);
    if (!last) return null;
    const today = await readinessOn(last.date);
    const lows = today!.factors.filter((f) => f.status === "low").sort((a, b) => a.z - b.z);
    const goods = today!.factors.filter((f) => f.status === "good").sort((a, b) => b.z - a.z);
    const avg = mean(scores.filter((s) => s.value !== null).map((s) => s.value!));
    const sentence = lows.length
        ? `Ce qui tire vers le bas : ${lows
              .slice(0, 2)
              .map((f) => `${inSentence(f.label)} (${f.note})`)
              .join(", ")}.`
        : goods.length
          ? `Point fort : ${inSentence(goods[0].label)} (${goods[0].note}).`
          : "Tous les signaux sont dans ta moyenne.";
    return {
        key: "recovery",
        label: "Récupération",
        value: String(last.value),
        numeric: last.value!,
        unit: "/ 100",
        status: last.value! >= 66 ? "good" : last.value! >= 52 ? "normal" : "low",
        statusLabel: labelOf(last.value!),
        delta: `moyenne ${Math.round(avg)} sur la période`,
        sentence,
        series: scores,
        inverted: false,
    };
}

async function sleep(dates: string[], today: string): Promise<HealthMetric | null> {
    const map = await dailyMetrics();
    const nights = (from: string, to: string) => {
        const values: { s: number; score: number | null }[] = [];
        for (let d = from; d <= to; d = addDays(d, 1)) {
            const m = map.get(d);
            if (m?.sleepSeconds) values.push({ s: m.sleepSeconds, score: m.sleepScore });
        }
        return values;
    };
    const week = nights(addDays(today, -6), today);
    const before = nights(addDays(today, -66), addDays(today, -7));
    if (week.length < 3) return null;
    const avg = mean(week.map((n) => n.s));
    const usual = before.length >= 14 ? mean(before.map((n) => n.s)) : null;
    const scores = week.map((n) => n.score).filter((v): v is number => v !== null);
    const score = scores.length ? Math.round(mean(scores)) : null;
    const gap = usual === null ? 0 : avg - usual;
    // Under 7 h on average is short for an athlete, whatever the habits
    const status: MetricStatus = avg < 6.5 * 3600 || gap < -40 * 60 ? "low" : avg >= 7.5 * 3600 && gap > -10 * 60 ? "good" : "normal";
    const spread = sd(week.map((n) => n.s));
    return {
        key: "sleep",
        label: "Sommeil",
        value: hours(avg),
        numeric: Math.round((avg / 3600) * 100) / 100,
        unit: "/ nuit",
        status,
        statusLabel: status === "good" ? "Bon" : status === "low" ? "Insuffisant" : "Correct",
        delta: usual === null ? null : Math.abs(gap) < 10 * 60 ? "comme d'habitude" : `${shortDuration(gap)} de ${gap > 0 ? "plus" : "moins"} que d'habitude`,
        sentence: `Moyenne des 7 dernières nuits${score ? `, score ${score}/100` : ""}. ${spread > 75 * 60 ? "Nuits très irrégulières d'un jour à l'autre." : "Durée régulière d'une nuit à l'autre."}`,
        series: dates.map((d) => ({ date: d, value: map.get(d)?.sleepSeconds ? Math.round((map.get(d)!.sleepSeconds! / 3600) * 10) / 10 : null })),
        inverted: false,
    };
}

async function hrv(dates: string[], today: string): Promise<HealthMetric | null> {
    const map = await dailyMetrics();
    const values = (from: string, to: string, pick: "hrvNight" | "restingHr") => {
        const out: number[] = [];
        for (let d = from; d <= to; d = addDays(d, 1)) {
            const v = map.get(d)?.[pick];
            if (v) out.push(v);
        }
        return out;
    };
    const week = values(addDays(today, -6), today, "hrvNight");
    const base = values(addDays(today, -66), addDays(today, -7), "hrvNight");
    if (week.length < 3 || base.length < 14) return null;
    // Normal range on a log scale, like the HRV-guided training studies
    const lnBase = base.map(Math.log);
    const m = mean(lnBase);
    const s = Math.max(sd(lnBase), 0.05);
    const avg = Math.exp(mean(week.map(Math.log)));
    const low = Math.exp(m - s);
    const high = Math.exp(m + s);
    const status: MetricStatus = avg < low ? "low" : avg > high ? "good" : "normal";

    const rhrWeek = values(addDays(today, -6), today, "restingHr");
    const rhrBase = values(addDays(today, -66), addDays(today, -7), "restingHr");
    const rhrGap = rhrWeek.length && rhrBase.length ? mean(rhrWeek) - mean(rhrBase) : null;
    const rhrText = rhrGap === null ? "" : Math.abs(rhrGap) < 1.5 ? ` FC au repos stable (${Math.round(mean(rhrWeek))} bpm).` : ` FC au repos ${rhrGap > 0 ? "en hausse" : "en baisse"} de ${dec(Math.abs(rhrGap), 0)} bpm (${Math.round(mean(rhrWeek))} bpm).`;

    const series = rolling(
        dates.map((d) => map.get(d)?.hrvNight ?? null),
        7,
    );
    return {
        key: "hrv",
        label: "VFC",
        value: String(Math.round(avg)),
        numeric: Math.round(avg),
        band: [Math.round(low), Math.round(high)],
        unit: "ms",
        status,
        statusLabel: status === "low" ? "Sous ta norme" : status === "good" ? "Au-dessus" : "Équilibrée",
        delta: `ta plage normale : ${Math.round(low)} à ${Math.round(high)} ms`,
        sentence: `Moyenne des 7 dernières nuits.${status === "low" ? " Une VFC sous ta plage signale une fatigue ou un stress : allège l'intensité quelques jours." : ""}${rhrText}`,
        series: dates.map((d, i) => ({ date: d, value: series[i] === null ? null : Math.round(series[i]!) })),
        inverted: false,
    };
}

async function fitness(dates: string[], today: string): Promise<HealthMetric | null> {
    // Steady runs only: no intervals, 30 min to 2 h, average HR 130-165; speed scaled linearly to 150 bpm
    const runs = await Activity.findAll({
        where: { type: { [Op.like]: "%running%" }, intervalTitle: null, startTimeLocal: { [Op.gte]: addDays(today, -400) }, averageHr: { [Op.between]: [130, 165] }, duration: { [Op.between]: [1800, 7200] } },
        attributes: ["startTimeLocal", "averageSpeed", "averageHr"],
    });
    const points = runs.filter((r) => r.averageSpeed).map((r) => ({ date: r.startTimeLocal.slice(0, 10), v: (r.averageSpeed! * EFFICIENCY_HR) / r.averageHr! }));
    const between = (from: string, to: string) => points.filter((p) => p.date >= from && p.date <= to).map((p) => p.v);
    const recent = between(addDays(today, -27), today);
    const before = between(addDays(today, -83), addDays(today, -28));
    if (recent.length < 3) return null;
    const now = median(recent);
    const gap = before.length >= 3 ? Math.round(1000 / median(before) - 1000 / now) : null; // s/km, positive = faster now
    const status: MetricStatus = gap === null || Math.abs(gap) < 3 ? "normal" : gap > 0 ? "good" : "low";

    // Sparkline: median of the 28 days ending on each date (smooth enough to read a trend)
    const series = dates.map((d) => {
        const window = between(addDays(d, -27), d);
        return { date: d, value: window.length >= 3 ? Math.round((1000 / median(window)) * 10) / 10 : null };
    });
    return {
        key: "fitness",
        label: "Condition",
        value: pace(now).replace("/km", ""),
        // Seconds per km gained at 150 bpm versus the two months before (negative = lost)
        numeric: gap ?? 0,
        unit: `/km à ${EFFICIENCY_HR} bpm`,
        status,
        statusLabel: status === "good" ? "En progrès" : status === "low" ? "En baisse" : "Stable",
        delta: gap === null ? null : gap === 0 ? "comme les 2 mois d'avant" : `${Math.abs(gap)} s/km ${gap > 0 ? "plus vite" : "plus lent"} que les 2 mois d'avant`,
        sentence: `Ton allure à ${EFFICIENCY_HR} bpm sur tes footings réguliers des 4 dernières semaines : plus elle baisse, plus tu es en forme.`,
        // Seconds per km: lower is better
        series,
        inverted: true,
    };
}

async function fatigue(days: number): Promise<HealthMetric | null> {
    const { today, series } = await loadSeries(days);
    if (!today || today.fatigue === null) return null;
    const status: MetricStatus = today.zone === "high" || today.zone === "overload" ? "low" : "good";
    return {
        key: "fatigue",
        label: "Fatigue",
        value: dec(today.fatigue),
        numeric: today.fatigue,
        unit: "/ 10",
        status,
        statusLabel: { fresh: "Frais", optimal: "Équilibrée", high: "Élevée", overload: "Surcharge" }[today.zone!],
        delta: "3 à 7 = équilibré",
        sentence: today.advice,
        series: series.map((s) => ({ date: s.date, value: s.fatigue })),
        inverted: false,
    };
}

export async function healthSummary(days: number): Promise<HealthMetric[]> {
    const today = toDateKey(new Date());
    const dates = Array.from({ length: days }, (_, i) => addDays(today, i - days + 1));
    const metrics = await Promise.all([recovery(dates), sleep(dates, today), hrv(dates, today), fitness(dates, today), fatigue(days)]);
    return metrics.filter((m): m is HealthMetric => m !== null);
}
