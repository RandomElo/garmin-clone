import { ArrowLeft } from "lucide-react";
import { lazy, Suspense, useMemo } from "react";
import { Link, useParams } from "react-router-dom";
import { LineChart, type Point } from "../components/LineChart";
import { IntervalCard, roleLabels } from "../components/IntervalCard";
import { SessionAnalysisCard } from "../components/SessionAnalysisCard";
import { CategoryPicker } from "../components/CategoryPicker";
import { Card, ErrorBox } from "../components/ui";
import { api, type ActivityWithDetail } from "../lib/api";
import { activityTypeLabel, formatDate, formatScore, formatDistance, formatDuration, formatNumber, formatPace, formatSpeed, isRunning, teLabel } from "../lib/format";
import type { LapInfo } from "../lib/intervals";
import { computeSplits } from "../lib/splits";
import { useApi } from "../lib/useApi";

// Leaflet is heavy: only load it when an activity has a GPS track
const RouteMap = lazy(() => import("../components/RouteMap").then((m) => ({ default: m.RouteMap })));

type Metric = [string, string];

function MetricGrid({ metrics }: { metrics: (Metric | null)[] }) {
    const shown = metrics.filter((m): m is Metric => m !== null && m[1] !== "–");
    return (
        <dl className="grid grid-cols-2 gap-x-6 gap-y-5 sm:grid-cols-3 lg:grid-cols-4">
            {shown.map(([label, value]) => (
                <div key={label}>
                    <dt className="text-sm text-slate-500 dark:text-slate-400">{label}</dt>
                    <dd className="text-xl font-semibold tabular-nums">{value}</dd>
                </div>
            ))}
        </dl>
    );
}

function HrZones({ zones }: { zones: number[] }) {
    const total = zones.reduce((a, b) => a + b, 0) || 1;
    // Ordinal data: one hue, lighter for easy zones, full strength for zone 5
    const opacity = [0.3, 0.45, 0.6, 0.8, 1];
    return (
        <div className="space-y-2">
            {[...zones].reverse().map((secs, i) => {
                const zone = 5 - i;
                const pct = (secs / total) * 100;
                return (
                    <div key={zone} className="flex items-center gap-3 text-sm">
                        <span className="w-14 shrink-0 text-slate-500 dark:text-slate-400">Zone {zone}</span>
                        <div className="h-5 flex-1 overflow-hidden rounded bg-slate-100 dark:bg-slate-800">
                            <div className="h-full rounded bg-accent" style={{ width: `${pct}%`, opacity: opacity[zone - 1] }} />
                        </div>
                        <span className="w-24 shrink-0 text-right tabular-nums">
                            {formatDuration(secs)} <span className="text-slate-500 dark:text-slate-400">· {Math.round(pct)} %</span>
                        </span>
                    </div>
                );
            })}
        </div>
    );
}

function TrainingEffect({ a }: { a: ActivityWithDetail }) {
    const bar = (label: string, value: number | null) =>
        value === null ? null : (
            <div>
                <div className="mb-1 flex justify-between text-sm">
                    <span className="text-slate-500 dark:text-slate-400">{label}</span>
                    <span className="font-semibold tabular-nums">{value.toFixed(1)} / 5</span>
                </div>
                <div className="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
                    <div className="h-full rounded-full bg-accent" style={{ width: `${(value / 5) * 100}%` }} />
                </div>
            </div>
        );
    const label = teLabel(a.teLabel);
    return (
        <div className="space-y-4">
            {label && <div className="text-lg font-semibold">{label}</div>}
            {bar("Aérobie", a.aerobicTe)}
            {bar("Anaérobie", a.anaerobicTe)}
            {a.trainingLoad !== null && (
                <div className="flex justify-between text-sm">
                    <span className="text-slate-500 dark:text-slate-400">Charge d'entraînement</span>
                    <span className="font-semibold tabular-nums">{Math.round(a.trainingLoad)}</span>
                </div>
            )}
        </div>
    );
}

export function ActivityDetail() {
    const { id = "" } = useParams();
    const { data: a, error } = useApi(() => api.activity(id), [id]);

    const charts = useMemo(() => {
        const samples = a?.detail?.samples ?? [];
        const xs = samples.map((s) => (s.d ?? 0) / 1000);
        const series = (pick: (i: number) => number | null): Point[] => xs.map((x, i) => ({ x, y: pick(i) }));
        return {
            hr: series((i) => samples[i].hr),
            splits: computeSplits(samples),
        };
    }, [a]);

    if (error) return <ErrorBox>{error}</ErrorBox>;
    if (!a) return null;

    const running = isRunning(a.type);
    const track = a.detail?.track ?? [];
    const intervals = a.intervals;
    // Lap rows: with their role and rep type when the session is an interval workout
    const lapRows: LapInfo[] = intervals?.laps ?? (a.detail?.laps ?? []).map((lap) => ({ lap, role: "other", group: null, rep: null }));
    const km = (x: number) => `${x.toLocaleString("fr-FR", { maximumFractionDigits: 1 })} km`;

    return (
        <>
            <Link to="/activites" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-accent dark:text-slate-400">
                <ArrowLeft className="size-4" /> Activités
            </Link>
            <div className="mb-6">
                <div className="text-sm font-medium text-accent">
                    {activityTypeLabel(a.type)}
                    {a.locationName ? ` · ${a.locationName}` : ""}
                </div>
                <h1 className="text-2xl font-semibold tracking-tight">{a.intervalTitle ?? a.name}</h1>
                <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                    {formatDate(a.startTimeLocal, { dateStyle: "full", timeStyle: "short" })}
                </p>
                <div className="mt-3">
                    <CategoryPicker key={a.id} id={a.id} category={a.category} manual={a.manualCategory !== null} />
                </div>
            </div>

            <Card>
                <MetricGrid
                    metrics={[
                        ["Distance", formatDistance(a.distance)],
                        ["Temps", formatDuration(a.duration)],
                        running ? ["Allure moyenne", formatPace(a.averageSpeed)] : ["Vitesse moyenne", formatSpeed(a.averageSpeed)],
                        ["FC moyenne", formatNumber(a.averageHr, "bpm")],
                        a.effort !== null ? ["Effort de la séance", `${formatScore(a.effort)} / 10`] : null,
                        a.load ? [`Charge 7 jours (${a.load.weekKm} km, ${a.load.weekSessions} séances)`, `${formatScore(a.load.charge)} / 20`] : null,
                        a.vo2Max ? ["VO2 max", formatNumber(a.vo2Max)] : null,
                    ]}
                />
            </Card>

            {a.analysis && (
                <div className="mt-6">
                    <SessionAnalysisCard analysis={a.analysis} />
                </div>
            )}

            {intervals && (
                <div className="mt-6">
                    <IntervalCard analysis={intervals} />
                </div>
            )}

            {track.length > 1 && (
                <Card title="Parcours" className="mt-6">
                    <Suspense fallback={<div className="h-72 rounded-xl bg-slate-100 md:h-96 dark:bg-slate-800" />}>
                        <RouteMap track={track} />
                    </Suspense>
                </Card>
            )}

            {charts.hr.length > 1 && (
                <Card title="Fréquence cardiaque" className="mt-6">
                    <LineChart ariaLabel="Fréquence cardiaque selon la distance" points={charts.hr} formatX={km} formatY={(v) => `${Math.round(v)} bpm`} />
                </Card>
            )}

            <div className="mt-6 grid gap-6 lg:grid-cols-2">
                {a.hrZones && (
                    <Card title="Zones cardio">
                        <HrZones zones={a.hrZones} />
                    </Card>
                )}
                {(a.aerobicTe !== null || a.trainingLoad !== null) && (
                    <Card title="Effet de l'entraînement">
                        <TrainingEffect a={a} />
                    </Card>
                )}
            </div>

            {running && (a.cadence || a.strideLength) && (
                <Card title="Dynamique de course" className="mt-6">
                    <MetricGrid
                        metrics={[
                            ["Cadence moyenne", formatNumber(a.cadence, "ppm")],
                            a.strideLength ? ["Longueur de foulée", `${(a.strideLength / 100).toLocaleString("fr-FR", { maximumFractionDigits: 2 })} m`] : null,
                        ]}
                    />
                </Card>
            )}

            {/* Kilometer splits mean little on an interval session: the reps table replaces them */}
            {charts.splits.length > 0 && !intervals && (
                <Card title="Temps intermédiaires (par km)" className="mt-6">
                    <div className="-mx-5 overflow-x-auto px-5">
                        <table className="w-full min-w-[28rem] text-sm tabular-nums">
                            <thead>
                                <tr className="text-left text-slate-500 dark:text-slate-400">
                                    <th className="py-2 font-medium">Km</th>
                                    <th className="py-2 font-medium">Temps</th>
                                    <th className="py-2 font-medium">{running ? "Allure" : "Vitesse"}</th>
                                    <th className="py-2 font-medium">FC moy.</th>
                                    <th className="py-2 font-medium">D+</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                {charts.splits.map((s) => {
                                    const speed = s.duration > 0 ? s.distance / s.duration : null;
                                    return (
                                        <tr key={s.km}>
                                            <td className="py-2">{s.distance < 999 ? (s.km - 1 + s.distance / 1000).toLocaleString("fr-FR", { maximumFractionDigits: 2 }) : s.km}</td>
                                            <td className="py-2">{formatDuration(s.duration)}</td>
                                            <td className="py-2 font-medium">{running ? formatPace(speed) : formatSpeed(speed)}</td>
                                            <td className="py-2">{formatNumber(s.averageHr)}</td>
                                            <td className="py-2">{formatNumber(s.elevationGain, "m")}</td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                </Card>
            )}

            {lapRows.length > 1 && (
                <Card title="Tours (montre)" className="mt-6">
                    <div className="-mx-5 overflow-x-auto px-5">
                        <table className="w-full min-w-[28rem] text-sm tabular-nums">
                            <thead>
                                <tr className="text-left text-slate-500 dark:text-slate-400">
                                    <th className="py-2 font-medium">Tour</th>
                                    {intervals && <th className="py-2 font-medium">Type</th>}
                                    <th className="py-2 font-medium">Distance</th>
                                    <th className="py-2 font-medium">Temps</th>
                                    <th className="py-2 font-medium">{running ? "Allure" : "Vitesse"}</th>
                                    <th className="py-2 font-medium">FC moy.</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                {lapRows.map(({ lap: l, role, group, rep }, i) => (
                                    <tr key={i} className={intervals && role !== "work" ? "text-slate-500 dark:text-slate-400" : ""}>
                                        <td className="py-2">{i + 1}</td>
                                        {intervals && (
                                            <td className="py-2 whitespace-nowrap">
                                                {role === "work" && group !== null ? (
                                                    <span className="rounded-full bg-accent/10 px-2 py-0.5 text-xs font-medium text-accent">
                                                        {intervals.groups[group].label} n°{rep}
                                                    </span>
                                                ) : (
                                                    <span className="text-xs">{roleLabels[role]}</span>
                                                )}
                                            </td>
                                        )}
                                        <td className="py-2">{formatDistance(l.distance)}</td>
                                        <td className="py-2">{formatDuration(l.duration)}</td>
                                        <td className="py-2 font-medium">{running ? formatPace(l.averageSpeed) : formatSpeed(l.averageSpeed)}</td>
                                        <td className="py-2">{formatNumber(l.averageHr)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </Card>
            )}

            {!a.detailsSyncedAt && <p className="mt-6 text-sm text-slate-500 dark:text-slate-400">Le tracé et les courbes de cette activité arrivent lors d'une prochaine synchro (rattrapage en cours).</p>}
        </>
    );
}
