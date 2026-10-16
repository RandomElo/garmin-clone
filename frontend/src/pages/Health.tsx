import { useState } from "react";
import { ChevronDown } from "lucide-react";
import { BarChart, type Bar } from "../components/BarChart";
import { BodyBatteryCard, type BatteryPoint } from "../components/BodyBatteryCard";
import { LineChart } from "../components/LineChart";
import { HealthMetricCard } from "../components/HealthMetricCard";
import { Card, EmptyState, ErrorBox, PageTitle } from "../components/ui";
import { api, type DailyMetric } from "../lib/api";
import { formatHours, formatNumber } from "../lib/format";
import { useApi } from "../lib/useApi";

const RANGES = [
    { days: 7, label: "7 j" },
    { days: 30, label: "30 j" },
    { days: 90, label: "3 mois" },
    { days: 365, label: "1 an" },
];

type NumericKey = { [K in keyof DailyMetric]: DailyMetric[K] extends number | null ? K : never }[keyof DailyMetric];

interface Bucket {
    key: string;
    label: string;
    days: DailyMetric[];
}

const noon = (date: string) => new Date(`${date}T12:00:00`);

/** Daily buckets up to 3 months; weekly averages beyond, so a year stays readable. */
function bucketize(days: DailyMetric[], weekly: boolean): Bucket[] {
    if (!weekly) return days.map((d) => ({ key: d.date, label: noon(d.date).toLocaleDateString("fr-FR", { day: "numeric", month: "numeric" }), days: [d] }));
    const buckets = new Map<string, Bucket>();
    for (const d of days) {
        const monday = noon(d.date);
        monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
        const key = monday.toISOString().slice(0, 10);
        if (!buckets.has(key)) buckets.set(key, { key, label: monday.toLocaleDateString("fr-FR", { day: "numeric", month: "short" }), days: [] });
        buckets.get(key)!.days.push(d);
    }
    return [...buckets.values()];
}

function avg(bucket: Bucket, key: NumericKey): number | null {
    const values = bucket.days.map((d) => d[key]).filter((v): v is number => v !== null);
    return values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
}

function simpleBars(buckets: Bucket[], key: NumericKey, display: (v: number | null) => string): Bar[] {
    return buckets.map((b) => {
        const value = avg(b, key);
        return { key: b.key, label: b.label, value, display: display(value) };
    });
}

const sleepSeries = [
    { label: "Profond", className: "bg-sleep-deep", key: "deepSleepSeconds" as const },
    { label: "Léger", className: "bg-sleep-light", key: "lightSleepSeconds" as const },
    { label: "Paradoxal", className: "bg-sleep-rem", key: "remSleepSeconds" as const },
    { label: "Éveillé", className: "bg-sleep-awake", key: "awakeSleepSeconds" as const },
];

export function Health() {
    const [days, setDays] = useState(30);
    const summary = useApi(() => api.healthSummary(days), [days]);
    // Day hovered on any trend line, pointed on all of them
    const [hoverDate, setHoverDate] = useState<string | null>(null);
    // Raw watch data: only fetched once the detail is opened
    const [showRaw, setShowRaw] = useState(false);
    const { data, error, loading } = useApi(() => (showRaw ? api.daily(days) : Promise.resolve(null)), [days, showRaw]);

    if (error) return <ErrorBox>{error}</ErrorBox>;

    const weekly = days > 90;
    const buckets = data ? bucketize(data, weekly) : [];
    const per = weekly ? " (moyenne hebdo)" : "";

    const sleepBars: Bar[] = buckets.map((b) => {
        const segments = sleepSeries.map((s) => avg(b, s.key));
        const total = segments.reduce<number>((sum, v) => sum + (v ?? 0), 0);
        const score = avg(b, "sleepScore");
        return {
            key: b.key,
            label: b.label,
            value: total || null,
            segments,
            display: total ? `${formatHours(total)}${score ? ` · score ${Math.round(score)}` : ""} (${sleepSeries.map((s, i) => `${s.label.toLowerCase()} ${formatHours(segments[i])}`).join(", ")})` : "–",
        };
    });

    const batteryPoints: BatteryPoint[] = buckets.map((b) => ({ key: b.key, label: b.label, high: avg(b, "bodyBatteryHigh"), low: avg(b, "bodyBatteryLow") }));

    const linePoints = (key: NumericKey) => buckets.map((b, i) => ({ x: i, y: avg(b, key) }));
    const xLabel = (x: number) => buckets[Math.round(x)]?.label ?? "";

    return (
        <>
            <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
                <PageTitle subtitle="Tout ce que mesure ta montre, résumé en cinq indicateurs">Santé</PageTitle>
                <div className="-mt-6 inline-flex rounded-xl border border-slate-200 bg-white p-1 text-sm dark:border-slate-800 dark:bg-slate-900" role="group" aria-label="Période">
                    {RANGES.map((r) => (
                        <button key={r.days} onClick={() => setDays(r.days)} aria-pressed={days === r.days} className={`rounded-lg px-3 py-1.5 font-medium transition ${days === r.days ? "bg-accent text-white" : "text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"}`}>
                            {r.label}
                        </button>
                    ))}
                </div>
            </div>

            {summary.error && <ErrorBox>{summary.error}</ErrorBox>}
            {summary.data && (
                // Flex rather than grid: an incomplete last row is centered
                <div className="flex flex-wrap justify-center gap-4">
                    {summary.data.map((m) => (
                        <div key={m.key} className="flex w-full sm:w-[calc(50%-0.5rem)] lg:w-[calc((100%-2rem)/3)] [&>section]:flex-1">
                            <HealthMetricCard metric={m} hoverDate={hoverDate} onHoverDate={setHoverDate} />
                        </div>
                    ))}
                </div>
            )}

            <button type="button" onClick={() => setShowRaw((v) => !v)} aria-expanded={showRaw} className="mx-auto mt-8 mb-4 flex items-center gap-2 text-sm font-medium text-accent">
                {showRaw ? "Masquer" : "Voir"} toutes les données de la montre
                <ChevronDown className={`size-4 transition-transform ${showRaw ? "rotate-180" : ""}`} />
            </button>

            {!showRaw ? null : !loading && !data?.length ? (
                <EmptyState>Aucune donnée de santé synchronisée.</EmptyState>
            ) : (
                data && (
                    <div className="grid gap-6 lg:grid-cols-2 [&>*]:min-w-0">
                        <Card title={`Sommeil${per}`} className="lg:col-span-2">
                            <BarChart ariaLabel="Durée de sommeil par phase" bars={sleepBars} series={sleepSeries} height={180} />
                        </Card>
                        <BodyBatteryCard title={`Body Battery${per}`} points={batteryPoints} />
                        <Card title={`VFC nocturne${per}`}>
                            <LineChart ariaLabel="VFC nocturne" points={linePoints("hrvNight")} formatX={xLabel} formatY={(v) => `${Math.round(v)} ms`} />
                        </Card>
                        <Card title={`FC au repos${per}`}>
                            <LineChart ariaLabel="Fréquence cardiaque au repos" points={linePoints("restingHr")} formatX={xLabel} formatY={(v) => `${Math.round(v)} bpm`} />
                        </Card>
                        <Card title={`Pas${per}`} className="lg:col-span-2">
                            <BarChart ariaLabel="Pas" bars={simpleBars(buckets, "steps", (v) => formatNumber(v, "pas"))} />
                        </Card>
                    </div>
                )
            )}
        </>
    );
}
