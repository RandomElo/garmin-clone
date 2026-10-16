import { CircleCheck, CircleDot, Flame, Gauge, Sparkles, TrendingDown, TriangleAlert, type LucideIcon } from "lucide-react";
import type { FactorStatus, VerdictKey } from "../lib/api";

/** Status colors, always paired with an icon and a label. */
const verdicts: Record<VerdictKey, { className: string; icon: LucideIcon }> = {
    excellent: { className: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300", icon: Flame },
    good: { className: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300", icon: CircleCheck },
    standard: { className: "bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300", icon: CircleDot },
    controlled: { className: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300", icon: Gauge },
    below: { className: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300", icon: TrendingDown },
    hard: { className: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300", icon: TriangleAlert },
    first: { className: "bg-violet-100 text-violet-800 dark:bg-violet-950 dark:text-violet-300", icon: Sparkles },
};

export function VerdictPill({ verdict, label }: { verdict: VerdictKey; label: string }) {
    const { className, icon: Icon } = verdicts[verdict];
    return (
        <span className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium ${className}`}>
            <Icon className="size-3.5" />
            {label}
        </span>
    );
}

const statuses: Record<FactorStatus, { dot: string; label: string }> = {
    good: { dot: "bg-emerald-500", label: "favorable" },
    normal: { dot: "bg-slate-400 dark:bg-slate-500", label: "habituel" },
    low: { dot: "bg-amber-500", label: "défavorable" },
};

/** Small colored dot with a hidden text equivalent for screen readers. */
export function StatusDot({ status }: { status: FactorStatus }) {
    return (
        <span className={`inline-block size-2 shrink-0 rounded-full ${statuses[status].dot}`}>
            <span className="sr-only">{statuses[status].label}</span>
        </span>
    );
}
