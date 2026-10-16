import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";

export function Card({ title, action, children, className = "" }: { title?: string; action?: ReactNode; children: ReactNode; className?: string }) {
    return (
        <section className={`rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900 ${className}`}>
            {(title || action) && (
                <div className="mb-4 flex items-center justify-between gap-3">
                    {title && <h2 className="text-sm font-semibold text-slate-500 dark:text-slate-400">{title}</h2>}
                    {action}
                </div>
            )}
            {children}
        </section>
    );
}

export function Stat({ icon: Icon, label, value, hint }: { icon: LucideIcon; label: string; value: string; hint?: string }) {
    return (
        <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
                <Icon className="size-4" />
                {label}
            </div>
            <div className="mt-2 text-2xl font-semibold tabular-nums">{value}</div>
            {hint && <div className="mt-1 text-xs text-slate-500 dark:text-slate-400">{hint}</div>}
        </div>
    );
}

export function PageTitle({ children, subtitle }: { children: ReactNode; subtitle?: string }) {
    return (
        <div className="mb-6">
            <h1 className="text-2xl font-semibold tracking-tight">{children}</h1>
            {subtitle && <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{subtitle}</p>}
        </div>
    );
}

export function EmptyState({ children }: { children: ReactNode }) {
    return <div className="rounded-2xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400">{children}</div>;
}

export function ErrorBox({ children }: { children: ReactNode }) {
    return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-300">{children}</div>;
}
