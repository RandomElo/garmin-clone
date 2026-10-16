import { useEffect, useRef, useState } from "react";
import { BatteryCharging, Dumbbell, HeartPulse, Info, Moon, TrendingUp, X, type LucideIcon } from "lucide-react";
import type { HealthMetric } from "../lib/api";
import { formatDuration } from "../lib/format";
import { StatusDot } from "./Verdict";

type Key = HealthMetric["key"];

const icons: Record<Key, LucideIcon> = {
    recovery: BatteryCharging,
    sleep: Moon,
    hrv: HeartPulse,
    fitness: TrendingUp,
    fatigue: Dumbbell,
};

interface Zone {
    to: number;
    label: string;
    className: string;
}

interface Explanation {
    /** The question the indicator answers, in a few words */
    question: string;
    /** What it is for, a few words */
    use: string;
    /** How it is measured, a few words */
    source: string;
    /** Scale of the explanation: start, then each zone up to its end */
    from: (m: HealthMetric) => number;
    zones: (m: HealthMetric) => Zone[];
    /** Data the indicator is computed from */
    inputs: string[];
}

// Status colors, always with a label in the zone: never color alone
const RED = "bg-red-400 dark:bg-red-500";
const AMBER = "bg-amber-400 dark:bg-amber-500";
const NEUTRAL = "bg-slate-300 dark:bg-slate-600";
const GREEN = "bg-emerald-400 dark:bg-emerald-500";
const GREEN_STRONG = "bg-emerald-600 dark:bg-emerald-400";
const SKY = "bg-sky-300 dark:bg-sky-500";

/** What each indicator answers, its scale and what it is made of, shown by the "i" button. */
const explanations: Record<Key, Explanation> = {
    recovery: {
        question: "Prêt pour une séance dure ?",
        use: "Savoir si tu peux faire une séance dure.",
        source: "Ta nuit, comparée à tes 30 derniers jours.",
        // Scores under 20 practically never happen: starting there leaves room for the zone names
        from: () => 20,
        zones: () => [
            { to: 38, label: "À ménager", className: RED },
            { to: 52, label: "Fatigué", className: AMBER },
            { to: 66, label: "Correct", className: NEUTRAL },
            { to: 82, label: "Prêt", className: GREEN },
            { to: 100, label: "Top", className: GREEN_STRONG },
        ],
        inputs: ["VFC", "Sommeil", "FC au repos", "Body Battery", "Stress", "Charge", "vs tes 30 derniers jours"],
    },
    sleep: {
        question: "Tu dors assez en ce moment ?",
        use: "Récupérer : le corps assimile l'entraînement en dormant.",
        source: "Montre au poignet, 7 dernières nuits.",
        from: () => 5,
        zones: () => [
            { to: 6.5, label: "Court", className: AMBER },
            { to: 7.5, label: "Correct", className: NEUTRAL },
            { to: 9, label: "Bon", className: GREEN },
        ],
        inputs: ["Durée", "Score Garmin", "7 dernières nuits", "vs 60 nuits d'avant"],
    },
    hrv: {
        question: "Ton système nerveux est reposé ?",
        use: "Repérer fatigue et stress avant de les sentir.",
        source: "Écart entre deux battements, la nuit.",
        from: (m) => Math.round((m.band?.[0] ?? m.numeric) * 0.75),
        zones: (m) => [
            { to: m.band?.[0] ?? m.numeric, label: "Bas", className: AMBER },
            { to: m.band?.[1] ?? m.numeric, label: "Ta normale", className: NEUTRAL },
            { to: Math.round((m.band?.[1] ?? m.numeric) * 1.25), label: "Haut", className: GREEN },
        ],
        inputs: ["VFC de la nuit", "7 dernières nuits", "vs 60 nuits", "FC au repos"],
    },
    fitness: {
        question: "Plus rapide à même effort ?",
        use: "Voir si ta forme monte, sans test.",
        source: "Allure des footings ramenée à 150 bpm.",
        from: () => -15,
        zones: () => [
            { to: -3, label: "En baisse", className: AMBER },
            { to: 3, label: "Stable", className: NEUTRAL },
            { to: 15, label: "En progrès", className: GREEN },
        ],
        inputs: ["Footings 30 min à 2 h", "FC 130 à 165", "Allure à 150 bpm", "4 semaines vs 8 d'avant"],
    },
    fatigue: {
        question: "Ta charge est raisonnable ?",
        use: "Éviter d'en faire trop d'un coup.",
        source: "Charge de la semaine contre ton mois.",
        from: () => 0,
        zones: () => [
            { to: 3, label: "Frais", className: SKY },
            { to: 7, label: "Équilibré", className: GREEN },
            { to: 8.5, label: "Élevé", className: AMBER },
            { to: 10, label: "Trop", className: RED },
        ],
        inputs: ["Charge 7 jours", "vs semaine moyenne du mois"],
    },
};

/** Value placed on the scale of the explanation, as the user reads it (s/km gained for the fitness). */
function scaleValue(m: HealthMetric): string {
    if (m.key === "fitness") return m.numeric === 0 ? "=" : `${m.numeric > 0 ? "+" : "−"}${Math.abs(m.numeric)} s/km`;
    return `${m.value}${m.unit && !m.unit.startsWith("/") ? ` ${m.unit}` : ""}`;
}

/** Colored zones with their name, and a marker on today's value. */
function Scale({ metric }: { metric: HealthMetric }) {
    const { from, zones } = explanations[metric.key];
    const start = from(metric);
    const list = zones(metric);
    const end = list[list.length - 1].to;
    const pct = (v: number) => ((Math.min(Math.max(v, start), end) - start) / (end - start)) * 100;
    // Width of each zone, in % of the scale
    const widths = list.map((z, k) => ((z.to - (k ? list[k - 1].to : start)) / (end - start)) * 100);
    return (
        <div className="mt-4">
            <div className="relative pt-6">
                <div className="absolute top-0 -translate-x-1/2 text-xs font-semibold whitespace-nowrap tabular-nums" style={{ left: `${Math.min(Math.max(pct(metric.numeric), 8), 92)}%` }}>
                    {scaleValue(metric)}
                </div>
                <div className="flex h-2.5 gap-0.5 overflow-hidden rounded-full">
                    {list.map((z, k) => (
                        <div key={z.label} className={z.className} style={{ width: `${widths[k]}%` }} />
                    ))}
                </div>
                <div className="absolute top-4 h-4.5 w-1 -translate-x-1/2 rounded-full bg-slate-900 ring-2 ring-white dark:bg-white dark:ring-slate-800" style={{ left: `${pct(metric.numeric)}%` }} />
            </div>
            <div className="mt-1.5 flex text-[11px] leading-tight text-slate-500 dark:text-slate-400">
                {list.map((z, k) => (
                    <span key={z.label} className="text-center whitespace-nowrap" style={{ width: `${widths[k]}%` }}>
                        {z.label}
                    </span>
                ))}
            </div>
        </div>
    );
}

/** Value of one point of the trend, in the unit of the metric. */
function formatPoint(key: Key, v: number): string {
    switch (key) {
        case "recovery":
            return `${Math.round(v)}/100`;
        case "sleep": {
            const m = Math.round(v * 60);
            return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")}`;
        }
        case "hrv":
            return `${Math.round(v)} ms`;
        case "fitness":
            return `${formatDuration(v)}/km`;
        case "fatigue":
            return `${v.toLocaleString("fr-FR", { maximumFractionDigits: 1 })}/10`;
    }
}

const dayLabel = (date: string) => new Date(`${date}T12:00:00`).toLocaleDateString("fr-FR", { day: "numeric", month: "short" });

/**
 * Trend line without axis: the card gives the value, the line where it goes. The hovered day is shared
 * with the other cards (same dates), so moving on one line points the same day everywhere.
 */
function Sparkline({ metric, hoverDate, onHoverDate }: { metric: HealthMetric; hoverDate: string | null; onHoverDate: (d: string | null) => void }) {
    const values = metric.series.map((p) => p.value);
    const known = values.filter((v): v is number => v !== null);
    if (known.length < 2) return null;
    const min = Math.min(...known);
    const max = Math.max(...known);
    const span = max - min || 1;
    const n = values.length;
    // Coordinates in % of the box: the SVG stretches, the dot is drawn in HTML so it stays round
    const x = (i: number) => (i / Math.max(n - 1, 1)) * 100;
    const y = (v: number) => {
        const t = (v - min) / span;
        // Pace (lower is better) is drawn upside down, so up always means better
        return 8 + (metric.inverted ? t : 1 - t) * 84;
    };
    let d = "";
    values.forEach((v, i) => {
        if (v === null) return;
        // A missing day (watch not synced, not worn) is bridged rather than cutting the line
        d += `${d ? "L" : "M"}${x(i)},${y(v)} `;
    });

    const index = hoverDate === null ? -1 : metric.series.findIndex((p) => p.date === hoverDate);
    const point = index >= 0 ? metric.series[index] : null;

    const onMove = (clientX: number, rect: DOMRect) => {
        const i = Math.round(((clientX - rect.left) / rect.width) * (n - 1));
        onHoverDate(metric.series[Math.max(0, Math.min(n - 1, i))].date);
    };

    return (
        <div className="mt-3">
            <div className="h-4 text-xs text-slate-500 tabular-nums dark:text-slate-400">
                {point && (
                    <>
                        {dayLabel(point.date)} : <span className="font-semibold text-slate-900 dark:text-slate-100">{point.value === null ? "pas de donnée" : formatPoint(metric.key, point.value)}</span>
                    </>
                )}
            </div>
            <div className="relative mt-1 h-10 cursor-crosshair touch-none" onMouseMove={(e) => onMove(e.clientX, e.currentTarget.getBoundingClientRect())} onMouseLeave={() => onHoverDate(null)} onTouchMove={(e) => onMove(e.touches[0].clientX, e.currentTarget.getBoundingClientRect())} onTouchEnd={() => onHoverDate(null)}>
                <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="absolute inset-0 h-full w-full overflow-visible" role="img" aria-label={`Tendance ${metric.label} sur la période`}>
                    <path d={d} fill="none" className="stroke-accent" strokeWidth={2} vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
                </svg>
                {point && (
                    <>
                        <div className="pointer-events-none absolute inset-y-0 w-px bg-slate-300 dark:bg-slate-600" style={{ left: `${x(index)}%` }} />
                        {point.value !== null && <div className="pointer-events-none absolute size-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-accent dark:border-slate-900" style={{ left: `${x(index)}%`, top: `${y(point.value)}%` }} />}
                    </>
                )}
            </div>
        </div>
    );
}

function InfoButton({ metric }: { metric: HealthMetric }) {
    const [open, setOpen] = useState(false);
    const box = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (!open) return;
        const close = (e: MouseEvent) => !box.current?.contains(e.target as Node) && setOpen(false);
        const esc = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
        document.addEventListener("mousedown", close);
        document.addEventListener("keydown", esc);
        return () => {
            document.removeEventListener("mousedown", close);
            document.removeEventListener("keydown", esc);
        };
    }, [open]);
    const { question, use, source, inputs } = explanations[metric.key];
    return (
        <div ref={box}>
            <button type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open} aria-label={`Comment lire : ${metric.label}`} className="grid size-6 place-items-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-accent dark:hover:bg-slate-800">
                <Info className="size-4" />
            </button>
            {open && (
                <div role="dialog" aria-label={metric.label} className="absolute top-12 left-1/2 z-20 w-[min(24rem,calc(100vw-2rem))] -translate-x-1/2 rounded-xl border border-slate-200 bg-white p-4 text-sm font-normal text-slate-900 shadow-lg dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100">
                    <button type="button" onClick={() => setOpen(false)} aria-label="Fermer" className="absolute top-2 right-2 grid size-6 place-items-center rounded-full text-slate-400 hover:text-slate-700 dark:hover:text-slate-200">
                        <X className="size-4" />
                    </button>
                    <p className="pr-6 font-semibold">{question}</p>
                    <Scale metric={metric} />
                    <ul className="mt-4 flex flex-wrap gap-1">
                        {inputs.map((i) => (
                            <li key={i} className="rounded-md bg-slate-100 px-1.5 py-0.5 text-xs text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                                {i}
                            </li>
                        ))}
                    </ul>
                    <dl className="mt-4 space-y-1 border-t border-slate-100 pt-3 text-xs dark:border-slate-700">
                        <div className="flex gap-2">
                            <dt className="w-14 shrink-0 font-semibold">Sert à</dt>
                            <dd className="text-slate-600 dark:text-slate-300">{use}</dd>
                        </div>
                        <div className="flex gap-2">
                            <dt className="w-14 shrink-0 font-semibold">Mesure</dt>
                            <dd className="text-slate-600 dark:text-slate-300">{source}</dd>
                        </div>
                    </dl>
                </div>
            )}
        </div>
    );
}

export function HealthMetricCard({ metric, hoverDate, onHoverDate }: { metric: HealthMetric; hoverDate: string | null; onHoverDate: (d: string | null) => void }) {
    const Icon = icons[metric.key];
    return (
        <section className="relative flex min-w-0 flex-col rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-center justify-between gap-2">
                <h2 className="flex items-center gap-2 text-sm font-semibold text-slate-500 dark:text-slate-400">
                    <Icon className="size-4" aria-hidden />
                    {metric.label}
                    <InfoButton metric={metric} />
                </h2>
                <span className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-600 dark:text-slate-300">
                    <StatusDot status={metric.status} />
                    {metric.statusLabel}
                </span>
            </div>
            <div className="mt-3 flex flex-wrap items-baseline gap-x-1.5">
                <span className="text-3xl font-semibold tabular-nums">{metric.value}</span>
                {metric.unit && <span className="text-sm text-slate-500 dark:text-slate-400">{metric.unit}</span>}
            </div>
            {metric.delta && <div className="truncate text-xs text-slate-500 dark:text-slate-400">{metric.delta}</div>}
            <div className="flex-1" />
            <Sparkline metric={metric} hoverDate={hoverDate} onHoverDate={onHoverDate} />
        </section>
    );
}
