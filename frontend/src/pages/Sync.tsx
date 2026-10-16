import { AlertTriangle, CheckCircle2, Circle, CircleSlash, Loader2, RefreshCw, Trash2, XCircle } from "lucide-react";
import { useState } from "react";
import { Gauge, ProgressBar } from "../components/Gauge";
import { Card, EmptyState, ErrorBox, PageTitle } from "../components/ui";
import { api, type StepStatus, type SyncRun, type SyncRunStatus, type SyncStatus, type SyncStep } from "../lib/api";
import { formatNumber, formatRelative } from "../lib/format";
import { formatEta, overallCoverage, ratio, runEta, runProgress } from "../lib/progress";
import { useSync } from "../lib/sync";
import { useApi } from "../lib/useApi";

const runStatus: Record<SyncRunStatus, { label: string; className: string }> = {
    running: { label: "En cours", className: "bg-accent/10 text-accent" },
    success: { label: "Réussie", className: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300" },
    partial: { label: "Partielle", className: "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300" },
    error: { label: "Échec", className: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300" },
};

function StatusPill({ status }: { status: SyncRunStatus }) {
    const { label, className } = runStatus[status];
    return <span className={`inline-block rounded-full px-2 py-0.5 text-xs font-medium ${className}`}>{label}</span>;
}

function StepIcon({ status }: { status: StepStatus }) {
    switch (status) {
        case "running":
            return <Loader2 className="size-5 animate-spin text-accent" />;
        case "success":
            return <CheckCircle2 className="size-5 text-emerald-600 dark:text-emerald-400" />;
        case "error":
            return <XCircle className="size-5 text-red-600 dark:text-red-400" />;
        case "skipped":
            return <CircleSlash className="size-5 text-slate-400" />;
        default:
            return <Circle className="size-5 text-slate-300 dark:text-slate-600" />;
    }
}

function stepDetail(step: SyncStep): string | null {
    if (step.status === "pending" || step.status === "skipped") return null;
    switch (step.key) {
        case "activities":
            // While running a full fetch, total is the Garmin count; afterwards count = new activities
            if (step.total) return `${formatNumber(step.count)} / ${formatNumber(step.total)}`;
            return `${step.count} nouvelle${step.count > 1 ? "s" : ""}`;
        case "details":
            return step.total ? `${step.count} / ${step.total} activités` : "à jour";
        case "wellness":
            return step.total ? `${step.count} / ${step.total} jours` : null;
        default:
            return null;
    }
}

function Steps({ run }: { run: SyncRun }) {
    return (
        <ol className="space-y-3">
            {run.steps.map((step) => {
                const detail = stepDetail(step);
                return (
                    <li key={step.key} className="flex items-start gap-3">
                        <StepIcon status={step.status} />
                        <div className="min-w-0 flex-1">
                            <div className="flex items-baseline justify-between gap-2">
                                <span className="font-medium">{step.label}</span>
                                {detail && <span className="shrink-0 text-sm tabular-nums text-slate-500 dark:text-slate-400">{detail}</span>}
                            </div>
                            {step.error && <p className="mt-0.5 text-sm break-words text-red-600 dark:text-red-400">{step.error}</p>}
                        </div>
                    </li>
                );
            })}
        </ol>
    );
}

function runKind(run: SyncRun): string {
    const kind = run.trigger === "auto" ? "Auto" : "Manuelle";
    return run.mode === "today" ? `${kind} (jour)` : kind;
}

/** "dans 8 minutes", "à 18:00 (prochain créneau)", "demain à 10:00" */
function nextAutoText(status: SyncStatus): string {
    const { nextAutoSyncAt, reason } = status.schedule;
    if (!nextAutoSyncAt || !reason) return "désactivée";
    if (status.current) return "après celle-ci";
    const at = new Date(nextAutoSyncAt);
    if (reason === "catch-up") return `${formatRelative(at)} (rattrapage)`;
    if (reason === "window") return formatRelative(at);
    const time = at.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    return at.toDateString() === new Date().toDateString() ? `à ${time}` : `demain à ${time}`;
}

function Toggle({ checked, disabled, onChange, label, description }: { checked: boolean; disabled?: boolean; onChange: (v: boolean) => void; label: string; description: string }) {
    return (
        <label className={`flex items-start gap-3 ${disabled ? "cursor-not-allowed opacity-60" : "cursor-pointer"}`}>
            <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} className="mt-1 size-4 shrink-0 accent-[var(--color-accent)]" />
            <span>
                <span className="block font-medium">{label}</span>
                <span className="block text-sm text-slate-500 dark:text-slate-400">{description}</span>
            </span>
        </label>
    );
}

/** The automation box: saved on the server, nothing runs on its own until it is ticked. */
function AutomationCard({ status }: { status: SyncStatus }) {
    const { refresh } = useSync();
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const save = async (patch: Partial<SyncStatus["prefs"]>) => {
        setSaving(true);
        try {
            await api.setPrefs(patch);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Enregistrement impossible");
        } finally {
            await refresh();
            setSaving(false);
        }
    };
    const windows = status.schedule.windows.join(" et ");
    return (
        <Card title="Automatisation" className="mt-6">
            <div className="space-y-4">
                <Toggle
                    checked={status.prefs.autoSync}
                    disabled={saving}
                    onChange={(autoSync) => void save({ autoSync })}
                    label="Synchro automatique"
                    description={`Toutes les ${status.intervalMinutes} min${windows ? `, ${windows}` : ""}, et rattrapage de l'historique si besoin. Décochée, rien ne se lance tout seul : seulement les boutons.`}
                />
            </div>
            {error && <p className="mt-3 text-sm text-red-600 dark:text-red-400">{error}</p>}
            <p className="mt-4 text-xs text-slate-500 dark:text-slate-400">Ce choix est enregistré : il reste après un redémarrage ou une réinitialisation des données.</p>
        </Card>
    );
}

function duration(run: SyncRun): string {
    if (!run.finishedAt) return "–";
    const s = (new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000;
    return s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)} min ${Math.round(s % 60)} s`;
}

function CoverageCard({ status }: { status: SyncStatus }) {
    const { scope, catchUp } = status;
    const { coverage } = catchUp;
    const estimate = catchUp.runsLeft
        ? `environ ${formatEta(catchUp.etaSeconds)} (${catchUp.runsLeft} synchro${catchUp.runsLeft > 1 ? "s" : ""} de ~${formatEta(catchUp.avgRunSeconds)}, une toutes les ${status.catchUpIntervalMinutes} min)`
        : null;
    return (
        <Card title="Données récupérées" className="lg:col-span-2">
            <div className="flex flex-col items-center">
                <Gauge value={overallCoverage(coverage)} label="de l'historique en local" />
                <p className="-mt-3 mb-5 text-center text-xs text-slate-500 dark:text-slate-400">{estimate ? `Rattrapage restant : ${estimate}` : "Historique complet"}</p>
            </div>
            <div className="space-y-4">
                <ProgressBar label="Activités" value={ratio(coverage.activities)} detail={`${formatNumber(coverage.activities.done)} / ${formatNumber(coverage.activities.total)}`} />
                <ProgressBar label="Détails et tracés GPS" value={ratio(coverage.details)} detail={`${formatNumber(coverage.details.done)} / ${formatNumber(coverage.details.total)}`} />
                <ProgressBar label={`Santé (${scope.wellnessDays} jours)`} value={ratio(coverage.wellness)} detail={`${formatNumber(coverage.wellness.done)} / ${formatNumber(coverage.wellness.total)}`} />
            </div>
        </Card>
    );
}

export function Sync() {
    const { status, error, starting, start, reset } = useSync();
    const [confirming, setConfirming] = useState(false);
    const history = useApi(() => api.syncHistory(15));

    const current = status?.current ?? null;
    const shown = current ?? status?.lastRun ?? null;
    const progress = current ? runProgress(current) : null;
    const eta = current && progress !== null ? runEta(current, progress) : null;

    return (
        <>
            <PageTitle subtitle="État de la synchronisation avec Garmin Connect">Synchronisation</PageTitle>

            {error && (
                <div className="mb-4">
                    <ErrorBox>{error}</ErrorBox>
                </div>
            )}

            {status && !status.configured && (
                <div className="mb-4 flex gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
                    <AlertTriangle className="size-5 shrink-0" />
                    <p>
                        Aucun identifiant Garmin. Renseigne <code>GARMIN_USERNAME</code> et <code>GARMIN_PASSWORD</code> dans le fichier
                        <code> .env</code>, ou <code>GARMIN_MOCK=true</code> pour tester avec des données fictives.
                    </p>
                </div>
            )}

            <div className="grid gap-6 lg:grid-cols-5">
                <Card className="lg:col-span-3" title={current ? "Synchronisation en cours" : "Dernière synchronisation"} action={shown && <StatusPill status={shown.status} />}>
                    {progress !== null && (
                        <div className="mb-5">
                            <ProgressBar label="Progression" value={progress / 100} detail={`${progress} %${eta !== null ? ` · encore ~${formatEta(eta)}` : ""}`} />
                        </div>
                    )}

                    {shown ? (
                        <>
                            <Steps run={shown} />
                            {shown.error && !current && <p className="mt-4 text-sm text-red-600 dark:text-red-400">{shown.error}</p>}
                            {!current && (
                                <p className="mt-4 text-xs text-slate-500 dark:text-slate-400">
                                    {runKind(shown)}, {formatRelative(shown.startedAt)} · durée {duration(shown)}
                                </p>
                            )}
                        </>
                    ) : (
                        <EmptyState>Aucune synchronisation pour l'instant.</EmptyState>
                    )}

                    <div className="mt-6 flex flex-col gap-2 sm:flex-row">
                        <button
                            onClick={() => void start("today")}
                            disabled={!status?.configured || Boolean(current) || starting}
                            className="inline-flex items-center justify-center gap-2 rounded-xl bg-accent px-4 py-2.5 font-medium text-white transition hover:bg-accent-strong disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            <RefreshCw className={`size-4 ${current || starting ? "animate-spin" : ""}`} />
                            {current ? "Synchronisation…" : "Synchroniser aujourd'hui"}
                        </button>
                        <button
                            onClick={() => void start("full")}
                            disabled={!status?.configured || Boolean(current) || starting}
                            title="Données du jour, puis un lot de l'historique manquant"
                            className="rounded-xl border border-slate-200 px-4 py-2.5 font-medium text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
                        >
                            Synchro complète
                        </button>
                    </div>

                    <dl className="mt-6 grid grid-cols-1 gap-x-6 gap-y-2 border-t border-slate-100 pt-4 text-sm sm:grid-cols-2 dark:border-slate-800">
                        {[
                            ["Source", status?.mock ? "Données fictives (démo)" : "Garmin Connect"],
                            ["Dernière réussite", formatRelative(status?.lastSuccessAt ?? null)],
                            [
                                "Synchro auto",
                                !status?.intervalMinutes || !status.prefs.autoSync
                                    ? "désactivée"
                                    : `toutes les ${status.intervalMinutes} min${status.schedule.windows.length ? `, ${status.schedule.windows.join(" et ")}` : ""}`,
                            ],
                            ["Prochaine auto", status ? nextAutoText(status) : "–"],
                        ].map(([label, value]) => (
                            <div key={label} className="flex justify-between gap-4">
                                <dt className="text-slate-500 dark:text-slate-400">{label}</dt>
                                <dd className="text-right font-medium">{value}</dd>
                            </div>
                        ))}
                    </dl>
                </Card>

                {status && <CoverageCard status={status} />}
            </div>

            {status && <AutomationCard status={status} />}


            <Card title="Historique" className="mt-6">
                {history.data?.length ? (
                    <div className="-mx-5 overflow-x-auto px-5">
                        <table className="w-full min-w-[36rem] text-sm">
                            <thead>
                                <tr className="text-left text-slate-500 dark:text-slate-400">
                                    <th className="py-2 font-medium">Date</th>
                                    <th className="py-2 font-medium">Type</th>
                                    <th className="py-2 font-medium">Statut</th>
                                    <th className="py-2 font-medium">Durée</th>
                                    <th className="py-2 font-medium">Détail</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                                {history.data.map((run) => {
                                    const count = (key: SyncStep["key"]) => run.steps.find((s) => s.key === key)?.count ?? 0;
                                    return (
                                        <tr key={run.id}>
                                            <td className="py-2 whitespace-nowrap">{new Date(run.startedAt).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" })}</td>
                                            <td className="py-2 whitespace-nowrap">{runKind(run)}</td>
                                            <td className="py-2">
                                                <StatusPill status={run.status} />
                                            </td>
                                            <td className="py-2 tabular-nums">{duration(run)}</td>
                                            <td className="py-2 text-slate-500 dark:text-slate-400" title={run.error ?? undefined}>
                                                {run.status === "error" && run.error ? run.error : `${count("activities")} nouvelle(s), ${count("details")} détail(s), ${count("wellness")} jour(s)`}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                ) : (
                    <EmptyState>Pas encore d'historique.</EmptyState>
                )}
            </Card>

            <Card title="Réinitialiser" className="mt-6">
                <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                    <p className="text-sm text-slate-600 dark:text-slate-300">
                        Supprime toutes les données locales (activités, santé, forme, historique des synchros) puis relance une synchro complète depuis
                        Garmin. La connexion Garmin est conservée.
                    </p>
                    {confirming ? (
                        <div className="flex shrink-0 gap-2">
                            <button onClick={() => setConfirming(false)} className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium dark:border-slate-700">
                                Annuler
                            </button>
                            <button
                                onClick={() => {
                                    setConfirming(false);
                                    void reset();
                                }}
                                className="inline-flex items-center gap-2 rounded-xl bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700"
                            >
                                <Trash2 className="size-4" />
                                Confirmer la suppression
                            </button>
                        </div>
                    ) : (
                        <button
                            onClick={() => setConfirming(true)}
                            disabled={Boolean(current) || starting}
                            title={current ? "Attends la fin de la synchro en cours" : undefined}
                            className="inline-flex shrink-0 items-center justify-center gap-2 rounded-xl border border-red-200 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-red-900 dark:text-red-400 dark:hover:bg-red-950/40"
                        >
                            <Trash2 className="size-4" />
                            Réinitialiser les données
                        </button>
                    )}
                </div>
            </Card>
        </>
    );
}
