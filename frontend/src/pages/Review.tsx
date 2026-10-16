import { useState } from "react";
import { ArrowLeft, Check, Trophy, X } from "lucide-react";
import { Link } from "react-router-dom";
import { Card, EmptyState, ErrorBox, PageTitle } from "../components/ui";
import { api, type ReviewItem } from "../lib/api";
import { categoryLabels } from "../lib/categories";
import { formatDate, formatDistance, formatDuration, formatNumber, formatPace } from "../lib/format";
import { useApi } from "../lib/useApi";

/** A run that looks like a race: its numbers, the rules it meets, and two buttons to settle it. */
function Candidate({ item, onDone }: { item: ReviewItem; onDone: () => void }) {
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const decide = async (race: boolean) => {
        setSaving(true);
        try {
            // "Not a race" keeps the detected kind, but as a choice: the run leaves the list for good
            await api.setCategory(item.id, race ? "competition" : item.detected);
            onDone();
        } catch (err) {
            setError(err instanceof Error ? err.message : "Enregistrement impossible");
            setSaving(false);
        }
    };
    return (
        <li className="py-4">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                <Link to={`/activites/${item.id}`} className="font-medium hover:text-accent">
                    {item.name}
                </Link>
                <span className="text-sm text-slate-500 dark:text-slate-400">{formatDate(item.startTimeLocal, { weekday: "short", day: "numeric", month: "short", year: "numeric" })}</span>
            </div>
            <div className="mt-0.5 text-sm text-slate-600 tabular-nums dark:text-slate-300">
                {formatDistance(item.distance)} · {formatDuration(item.duration)} · {formatPace(item.averageSpeed)} · {formatNumber(item.averageHr, "bpm")}
            </div>
            <ul className="mt-2 flex flex-wrap gap-1.5">
                {item.reasons.map((r) => (
                    <li key={r} className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                        <Check className="size-3.5 text-accent" aria-hidden />
                        {r}
                    </li>
                ))}
            </ul>
            <div className="mt-3 flex flex-wrap gap-2">
                <button type="button" disabled={saving} onClick={() => void decide(true)} className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent-strong disabled:opacity-60">
                    <Trophy className="size-4" aria-hidden />
                    C'est une compétition
                </button>
                <button type="button" disabled={saving} onClick={() => void decide(false)} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-sm font-medium hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:hover:bg-slate-800">
                    <X className="size-4" aria-hidden />
                    Non, {categoryLabels[item.detected].toLowerCase()}
                </button>
            </div>
            {error && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{error}</p>}
        </li>
    );
}

export function Review() {
    const { data, error, loading } = useApi(() => api.review());
    // Settled runs leave the list at once, without reloading it
    const [done, setDone] = useState<Set<number>>(new Set());
    const items = (data ?? []).filter((i) => !done.has(i.id));

    if (error) return <ErrorBox>{error}</ErrorBox>;

    return (
        <>
            <Link to="/activites" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-accent dark:text-slate-400">
                <ArrowLeft className="size-4" /> Activités
            </Link>
            <PageTitle subtitle={data ? `${items.length} course${items.length > 1 ? "s" : ""} à vérifier` : undefined}>À classer</PageTitle>
            <p className="-mt-3 mb-6 text-sm text-slate-600 dark:text-slate-300">Ces sorties ressemblent à une compétition d'après des règles simples : distance officielle, FC moyenne très haute, allure parmi tes plus rapides, nom de course, Training Effect très élevé, départ le week-end en matinée. Une fois classées, elles ne reviennent plus.</p>
            {loading && !data ? null : items.length ? (
                <Card>
                    <ul className="-my-4 divide-y divide-slate-100 dark:divide-slate-800">
                        {items.map((item) => (
                            <Candidate key={item.id} item={item} onDone={() => setDone((prev) => new Set(prev).add(item.id))} />
                        ))}
                    </ul>
                </Card>
            ) : (
                <EmptyState>Tout est classé.</EmptyState>
            )}
        </>
    );
}
