import { Loader2, Trophy } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ActivityRow } from "../components/ActivityRow";
import { FilterMenu } from "../components/FilterMenu";
import { Card, EmptyState, ErrorBox, PageTitle } from "../components/ui";
import { ACTIVITY_FILTERS, api, type Activity, type ActivityFilter } from "../lib/api";
import { formatNumber } from "../lib/format";
import { useApi } from "../lib/useApi";
import { useSync } from "../lib/sync";

const PAGE_SIZE = 30;

const isFilter = (v: string | null): v is ActivityFilter => ACTIVITY_FILTERS.includes(v as ActivityFilter);

export function Activities() {
    // Kept in the URL so coming back from an activity keeps the filter
    const [params, setParams] = useSearchParams();
    const type = params.get("type");
    const filter: ActivityFilter = isFilter(type) ? type : "all";
    const [counts, setCounts] = useState<Partial<Record<ActivityFilter, number>>>({});
    // Runs that look like a race and wait for a decision
    const review = useApi(() => api.review());

    return (
        <>
            <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="[&>div]:mb-0">
                    <PageTitle subtitle={counts.all !== undefined ? `${formatNumber(counts.all)} activités synchronisées` : undefined}>Activités</PageTitle>
                </div>
<div className="flex flex-col gap-2 sm:flex-row sm:items-center">
                    {review.data && review.data.length > 0 && (
                        <Link to="/a-classer" className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm font-medium shadow-sm hover:border-accent/60 dark:border-slate-800 dark:bg-slate-900">
                            <Trophy className="size-4 text-accent" aria-hidden />
                            À classer
                            <span className="rounded-full bg-accent px-1.5 text-xs text-white tabular-nums">{review.data.length}</span>
                        </Link>
                    )}
                    <FilterMenu value={filter} counts={counts} onChange={(f) => setParams(f === "all" ? {} : { type: f }, { replace: true })} />
                </div>
            </div>
            {/* Keyed by filter: a new filter starts a fresh list (first page, infinite scroll) */}
            <ActivityList key={filter} filter={filter} onCounts={setCounts} />
        </>
    );
}

function ActivityList({ filter, onCounts }: { filter: ActivityFilter; onCounts: (counts: Record<ActivityFilter, number>) => void }) {
    const { dataVersion } = useSync();
    const [items, setItems] = useState<Activity[]>([]);
    const [total, setTotal] = useState<number | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const sentinel = useRef<HTMLDivElement>(null);
    // Guards against the observer firing twice before the first page arrives
    const busy = useRef(false);

    const loadMore = useCallback(
        async (offset: number, replace = false) => {
            if (busy.current) return;
            busy.current = true;
            setLoading(true);
            try {
                const page = await api.activities(PAGE_SIZE, offset, filter);
                setItems((prev) => (replace ? page.items : [...prev, ...page.items]));
                setTotal(page.total);
                if (page.counts) onCounts(page.counts);
                setError(null);
            } catch (err) {
                setError(err instanceof Error ? err.message : "Erreur de chargement");
            } finally {
                busy.current = false;
                setLoading(false);
            }
        },
        [filter, onCounts],
    );

    // Number of items shown, read by the refresh effect below without re-running it on every page
    const loaded = useRef(0);
    useEffect(() => {
        loaded.current = items.length;
    }, [items.length]);

    // First page on mount. After a sync, reload as many items as are shown (in API-sized chunks)
    // so new activities appear at the top without losing the scroll position.
    useEffect(() => {
        if (loaded.current === 0) {
            void loadMore(0, true);
            return;
        }
        let cancelled = false;
        (async () => {
            const wanted = loaded.current;
            const all: Activity[] = [];
            let count = 0;
            while (all.length < wanted) {
                const page = await api.activities(Math.min(100, wanted - all.length), all.length, filter);
                all.push(...page.items);
                count = page.total;
                if (page.counts) onCounts(page.counts);
                if (page.items.length === 0) break;
            }
            if (!cancelled) {
                setItems(all);
                setTotal(count);
            }
        })().catch(() => undefined);
        return () => {
            cancelled = true;
        };
    }, [loadMore, dataVersion, filter, onCounts]);

    const hasMore = total === null || items.length < total;

    // Load the next page when the bottom of the list comes into view
    useEffect(() => {
        const el = sentinel.current;
        if (!el || !hasMore || error) return;
        const observer = new IntersectionObserver(([entry]) => entry.isIntersecting && void loadMore(items.length), { rootMargin: "600px" });
        observer.observe(el);
        return () => observer.disconnect();
    }, [items.length, hasMore, error, loadMore]);

    return (
        <>
            {error && (
                <div className="mb-4">
                    <ErrorBox>{error}</ErrorBox>
                </div>
            )}
            <Card>
                {items.length ? (
                    <ul className="-mx-2 divide-y divide-slate-100 dark:divide-slate-800">
                        {items.map((a) => (
                            <ActivityRow key={a.id} activity={a} />
                        ))}
                    </ul>
                ) : (
                    !loading && total !== null && <EmptyState>{filter === "all" ? "Aucune activité pour l'instant." : "Aucune activité de ce type."}</EmptyState>
                )}
                <div ref={sentinel} />
                {loading && (
                    <div className="flex justify-center py-4 text-slate-400">
                        <Loader2 className="size-5 animate-spin" />
                    </div>
                )}
                {!hasMore && items.length > PAGE_SIZE && <p className="py-3 text-center text-xs text-slate-500 dark:text-slate-400">Tout l'historique est affiché.</p>}
            </Card>
        </>
    );
}
