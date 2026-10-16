import type { ReactNode } from "react";
import { AlertTriangle, ArrowDownRight, ArrowUpRight, BatteryFull, Gauge, type LucideIcon } from "lucide-react";
import { Link } from "react-router-dom";
import { Card } from "./ui";
import type { FatigueZone, LoadSummary } from "../lib/api";
import { formatScore } from "../lib/format";

/** Status colors, always paired with an icon and a label. */
export const zones: Record<FatigueZone, { label: string; className: string; icon: LucideIcon }> = {
    fresh: { label: "Frais", className: "bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300", icon: BatteryFull },
    optimal: { label: "Optimal", className: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300", icon: Gauge },
    high: { label: "Élevée", className: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300", icon: AlertTriangle },
    overload: { label: "Surcharge", className: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300", icon: AlertTriangle },
};

export function ZonePill({ zone }: { zone: FatigueZone }) {
    const { label, className, icon: Icon } = zones[zone];
    return (
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${className}`}>
            <Icon className="size-3.5" />
            {label}
        </span>
    );
}

function Meter({ value, max }: { value: number; max: number }) {
    return (
        <div className="mt-2 h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
            <div className="h-full rounded-full bg-accent" style={{ width: `${Math.min(value / max, 1) * 100}%` }} />
        </div>
    );
}

function Score({ label, value, max, hint, extra }: { label: ReactNode; value: number | null; max: number; hint: string; extra?: ReactNode }) {
    return (
        <div>
            <div className="flex items-center justify-between gap-2 text-sm text-slate-500 dark:text-slate-400">{label}</div>
            <div className="flex flex-wrap items-baseline gap-x-2">
                <span className="text-3xl font-semibold tabular-nums">{formatScore(value)}</span>
                <span className="text-slate-500 dark:text-slate-400">/ {max}</span>
                {extra}
            </div>
            <Meter value={value ?? 0} max={max} />
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{hint}</p>
        </div>
    );
}

const plural = (n: number, word: string) => `${n} ${word}${n > 1 ? "s" : ""}`;

export function LoadCard({ today, link = true }: { today: NonNullable<LoadSummary["today"]>; link?: boolean }) {
    const trend = today.weekTrend;
    return (
        <Card
            title="Charge d'entraînement"
            action={
                link ? (
                    <Link to="/charge" className="text-sm font-medium text-accent hover:underline">
                        Évolution
                    </Link>
                ) : undefined
            }
        >
            <div className="grid gap-6 sm:grid-cols-3">
                <Score
                    label="7 derniers jours"
                    value={today.week}
                    max={20}
                    hint={`${today.weekKm} km, ${plural(today.weekSessions, "séance")}`}
                    extra={
                        trend !== null &&
                        trend !== 0 && (
                            <span className="inline-flex items-center text-sm text-slate-600 tabular-nums dark:text-slate-300">
                                {trend > 0 ? <ArrowUpRight className="size-4" /> : <ArrowDownRight className="size-4" />}
                                {trend > 0 ? "+" : "−"}
                                {formatScore(Math.abs(trend))}
                            </span>
                        )
                    }
                />
                <Score label="4 dernières semaines" value={today.month} max={20} hint={`${today.monthKm} km, ${plural(today.monthSessions, "séance")}`} />
                <Score
                    label={
                        <>
                            Fatigue
                            {today.zone && <ZonePill zone={today.zone} />}
                        </>
                    }
                    value={today.fatigue}
                    max={10}
                    hint="Ta semaine comparée à ton mois (3 à 7 = équilibré)"
                />
            </div>
            <p className="mt-5 rounded-xl bg-slate-50 p-3 text-sm dark:bg-slate-800/60">{today.advice}</p>
            <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">Charge sur 20 : 20 = tes semaines (ou tes mois) les plus chargés depuis le début de l'historique.</p>
        </Card>
    );
}
