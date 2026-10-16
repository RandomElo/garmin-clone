import { useState, type ReactNode } from "react";
import { ArrowDownRight, ArrowUpRight, ChevronDown, HeartPulse, Route, Weight, type LucideIcon } from "lucide-react";
import { Link } from "react-router-dom";
import { StatusDot } from "./Verdict";
import type { Briefing, LoadComparison, Readiness } from "../lib/api";
import { formatDate, formatScore } from "../lib/format";

export type Panel = "load" | "readiness";

function Trend({ delta, unit, digits = 0 }: { delta: number; unit: string; digits?: number }) {
    const rounded = Number(delta.toFixed(digits));
    if (rounded === 0) return <span className="text-xs text-slate-500 dark:text-slate-400">stable</span>;
    return (
        <span className="inline-flex items-center text-xs font-medium text-slate-600 tabular-nums dark:text-slate-300">
            {rounded > 0 ? <ArrowUpRight className="size-3.5" /> : <ArrowDownRight className="size-3.5" />}
            {rounded > 0 ? "+" : "−"}
            {Math.abs(rounded).toLocaleString("fr-FR")}
            {unit}
        </span>
    );
}

function Tile({ icon: Icon, label, value, unit, footer, open, onClick }: { icon: LucideIcon; label: string; value: string; unit?: string; footer: ReactNode; open?: boolean; onClick?: () => void }) {
    const body = (
        <>
            <div className="flex items-center justify-between gap-2 text-sm text-slate-500 dark:text-slate-400">
                <span className="flex items-center gap-2">
                    <Icon className="size-4" />
                    {label}
                </span>
                {onClick && <ChevronDown className={`size-4 transition-transform ${open ? "rotate-180" : ""}`} />}
            </div>
            <div className="mt-2 flex items-baseline gap-1">
                <span className="text-3xl font-semibold tabular-nums">{value}</span>
                {unit && <span className="text-slate-500 dark:text-slate-400">{unit}</span>}
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-x-2 text-xs text-slate-500 dark:text-slate-400">{footer}</div>
        </>
    );
    const base = "rounded-2xl border bg-white p-4 text-left shadow-sm dark:bg-slate-900";
    if (!onClick) return <div className={`${base} border-slate-200 dark:border-slate-800`}>{body}</div>;
    return (
        <button type="button" onClick={onClick} aria-expanded={open} className={`${base} transition-colors hover:border-accent/60 focus-visible:outline-2 focus-visible:outline-accent ${open ? "border-accent ring-1 ring-accent" : "border-slate-200 dark:border-slate-800"}`}>
            {body}
        </button>
    );
}

export function HomeTiles({ briefing, panel, onToggle }: { briefing: Briefing; panel: Panel | null; onToggle: (p: Panel) => void }) {
    const { km, load, readiness } = briefing;
    return (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Tile
                icon={Route}
                label="Cette semaine"
                value={km.value.toLocaleString("fr-FR", { maximumFractionDigits: 1 })}
                unit="km"
                footer={
                    <span>
                        {km.sessions} sortie{km.sessions > 1 ? "s" : ""} depuis lundi · semaine dernière : {km.lastWeek.toLocaleString("fr-FR", { maximumFractionDigits: 1 })} km
                    </span>
                }
            />
            <Tile
                icon={Weight}
                label="Charge, 7 jours"
                value={formatScore(load.value)}
                unit="/ 20"
                open={panel === "load"}
                onClick={() => onToggle("load")}
                footer={
                    <>
                        <Trend delta={load.delta} unit="" digits={1} />
                        <span>vs {formatScore(load.previous)} les 3 semaines d'avant · voir pourquoi</span>
                    </>
                }
            />
            <Tile icon={HeartPulse} label="Forme du jour" value={readiness ? String(readiness.score) : "–"} unit={readiness ? "/ 100" : undefined} open={panel === "readiness"} onClick={readiness ? () => onToggle("readiness") : undefined} footer={readiness ? <span>{readiness.label} · sommeil, VFC, FC au repos</span> : <span>Pas encore de nuit enregistrée</span>} />
        </div>
    );
}

/* ---------- Load: what makes the difference ---------- */

/** Two thin bars: the last 7 days (accent) over the average of the 3 weeks before (neutral reference). */
function PairedBars({ current, before, max, label }: { current: number; before: number; max: number; label: string }) {
    const bar = (value: number, className: string, title: string) => (
        <div className="flex items-center gap-2" title={title}>
            <div className="h-2.5 flex-1">
                <div className={`h-full rounded-r ${className}`} style={{ width: `${Math.max((value / max) * 100, value > 0 ? 1.5 : 0)}%` }} />
            </div>
            <span className="w-9 shrink-0 text-right text-xs text-slate-600 tabular-nums dark:text-slate-300">{formatScore(value)}</span>
        </div>
    );
    return (
        <div className="space-y-0.5">
            {bar(current, "bg-accent", `${label}, 7 derniers jours : ${formatScore(current)} pts`)}
            {bar(before, "bg-slate-400 dark:bg-slate-600", `${label}, moyenne avant : ${formatScore(before)} pts`)}
        </div>
    );
}

export function LoadPanel({ comparison }: { comparison: LoadComparison }) {
    const rows = comparison.categories;
    const max = Math.max(comparison.current.points, comparison.avgPoints, 1);
    return (
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <h2 className="mb-2 text-sm font-semibold text-slate-500 dark:text-slate-400">Ce qui fait la différence</h2>
            <p className="font-medium leading-relaxed">{comparison.explanation[0]}</p>

            <div className="mt-4 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600 dark:text-slate-300">
                <span className="inline-flex items-center gap-1.5">
                    <span className="h-2.5 w-4 rounded-sm bg-accent" /> 7 derniers jours
                </span>
                <span className="inline-flex items-center gap-1.5">
                    <span className="h-2.5 w-4 rounded-sm bg-slate-400 dark:bg-slate-600" /> moyenne des 3 semaines d'avant
                </span>
            </div>

            <ul className="mt-3 divide-y divide-slate-100 dark:divide-slate-800">
                <li className="grid grid-cols-[7.5rem_1fr_3.5rem] items-center gap-3 py-2.5">
                    <span className="text-sm font-semibold">Total</span>
                    <PairedBars current={comparison.current.points} before={comparison.avgPoints} max={max} label="Total" />
                    <span className="text-right">
                        <Trend delta={comparison.delta} unit="" digits={1} />
                    </span>
                </li>
                {rows.map((c) => {
                    const sessions = comparison.current.sessions.filter((s) => s.category === c.category);
                    return (
                        <li key={c.category} className="grid grid-cols-[7.5rem_1fr_3.5rem] items-center gap-3 py-2.5">
                            <div className="min-w-0">
                                <div className="text-sm">{c.label}</div>
                                <div className="text-xs text-slate-500 dark:text-slate-400">
                                    {c.count} contre {c.avgCount.toLocaleString("fr-FR")}
                                </div>
                            </div>
                            <div className="min-w-0">
                                <PairedBars current={c.points} before={c.avgPoints} max={max} label={c.label} />
                                {sessions.length > 0 && (
                                    <div className="mt-1 truncate text-xs text-slate-500 dark:text-slate-400">
                                        {sessions.map((s, i) => (
                                            <span key={s.id}>
                                                {i > 0 && ", "}
                                                <Link to={`/activites/${s.id}`} className="hover:text-accent hover:underline">
                                                    {s.title}
                                                </Link>
                                            </span>
                                        ))}
                                    </div>
                                )}
                            </div>
                            <span className="text-right">
                                <Trend delta={c.delta} unit="" digits={1} />
                            </span>
                        </li>
                    );
                })}
            </ul>
            <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">En points de charge sur 20 (20 = tes semaines les plus chargées). Le nombre à gauche compte les séances.</p>
        </section>
    );
}

/* ---------- Readiness: factor by factor ---------- */

export function ReadinessPanel({ readiness }: { readiness: Readiness }) {
    const [today] = useState(() => {
        const now = new Date();
        return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
    });
    return (
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <h2 className="mb-1 text-sm font-semibold text-slate-500 dark:text-slate-400">Forme du jour, facteur par facteur</h2>
            {readiness.date !== today && <p className="mb-3 text-xs text-slate-500 dark:text-slate-400">Dernière nuit synchronisée : {formatDate(`${readiness.date}T12:00:00`, { weekday: "long", day: "numeric", month: "long" })}.</p>}
            <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                {readiness.factors.map((f) => (
                    <li key={f.key} className="flex items-center justify-between gap-3 py-2.5">
                        <div className="flex min-w-0 items-center gap-2.5">
                            <StatusDot status={f.status} />
                            <div className="min-w-0">
                                <div className="text-sm font-medium">{f.label}</div>
                                <div className="text-xs text-slate-500 dark:text-slate-400">
                                    {f.note}
                                    {f.baselineDisplay && ` · habituel ${f.baselineDisplay}`}
                                </div>
                            </div>
                        </div>
                        <span className="shrink-0 font-semibold tabular-nums">{f.display}</span>
                    </li>
                ))}
            </ul>
            <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">Chaque valeur est comparée à la tienne sur les 30 derniers jours (60 pour la VFC), pas à une norme générale. La VFC et le sommeil pèsent le plus : ce sont les signaux les mieux étudiés pour décider d'une séance dure ou facile.</p>
        </section>
    );
}
