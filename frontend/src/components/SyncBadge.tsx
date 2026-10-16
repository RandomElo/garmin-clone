import { AlertTriangle, CheckCircle2, CircleSlash, RefreshCw, WifiOff } from "lucide-react";
import { Link } from "react-router-dom";
import { formatRelative } from "../lib/format";
import { runProgress } from "../lib/progress";
import { useSync } from "../lib/sync";

export function SyncBadge() {
    const { status, error } = useSync();

    let icon = <RefreshCw className="size-4 animate-spin" />;
    let label = "Chargement…";
    let tone = "text-slate-500 dark:text-slate-400";

    if (error && !status) {
        icon = <WifiOff className="size-4" />;
        label = error;
        tone = "text-red-600 dark:text-red-400";
    } else if (status && !status.configured) {
        icon = <CircleSlash className="size-4" />;
        label = "Garmin non configuré";
    } else if (status?.current) {
        label = `Synchronisation… ${runProgress(status.current)} %`;
        tone = "text-accent";
    } else if (status?.lastRun?.status === "error") {
        icon = <AlertTriangle className="size-4" />;
        label = "Échec de la dernière synchro";
        tone = "text-red-600 dark:text-red-400";
    } else if (status?.lastRun?.status === "partial") {
        icon = <AlertTriangle className="size-4" />;
        label = `Synchro partielle ${formatRelative(status.lastRun.finishedAt)}`;
        tone = "text-amber-600 dark:text-amber-400";
    } else if (status) {
        icon = <CheckCircle2 className="size-4" />;
        label = `Synchronisé ${formatRelative(status.lastSuccessAt)}`;
        tone = "text-emerald-700 dark:text-emerald-400";
    }

    return (
        <Link to="/synchro" className={`inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-1.5 text-sm font-medium shadow-sm transition hover:border-slate-300 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-slate-700 ${tone}`}>
            {icon}
            <span className="max-w-[16rem] truncate">{label}</span>
        </Link>
    );
}

/** One tap to fetch today's data, from any page. */
export function QuickSyncButton() {
    const { status, starting, start } = useSync();
    const running = Boolean(status?.current) || starting;
    return (
        <button
            onClick={() => void start("today")}
            disabled={!status?.configured || running}
            aria-label="Synchroniser les données du jour"
            title="Synchroniser les données du jour"
            className="grid size-9 shrink-0 place-items-center rounded-full border border-slate-200 bg-white text-slate-600 shadow-sm transition hover:border-slate-300 hover:text-accent disabled:cursor-not-allowed disabled:opacity-60 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300"
        >
            <RefreshCw className={`size-4 ${running ? "animate-spin" : ""}`} />
        </button>
    );
}
