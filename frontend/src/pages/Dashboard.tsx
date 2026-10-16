import { useState } from "react";
import { ArrowRight } from "lucide-react";
import { Link } from "react-router-dom";
import { ActivityRow } from "../components/ActivityRow";
import { HomeTiles, LoadPanel, ReadinessPanel, type Panel } from "../components/HomeCards";
import { VerdictPill } from "../components/Verdict";
import { WeeklyChart } from "../components/WeeklyChart";
import { Card, EmptyState, ErrorBox } from "../components/ui";
import { api } from "../lib/api";
import { formatDate } from "../lib/format";
import { useApi } from "../lib/useApi";

/** Profile picture from Garmin, initials when there is none or it fails to load. */
function Avatar({ photo, name }: { photo: string | null; name: string | null }) {
    const [failed, setFailed] = useState(false);
    if (photo && !failed) return <img src={photo} alt="" referrerPolicy="no-referrer" onError={() => setFailed(true)} className="size-12 shrink-0 rounded-full object-cover ring-2 ring-white dark:ring-slate-900" />;
    return <div className="grid size-12 shrink-0 place-items-center rounded-full bg-accent/10 text-lg font-semibold text-accent">{name?.[0]?.toUpperCase() ?? "?"}</div>;
}

export function Dashboard() {
    const briefing = useApi(() => api.briefing());
    const summary = useApi(() => api.summary(26, "running"));
    const recent = useApi(() => api.activities(5));
    const [panel, setPanel] = useState<Panel | null>(null);
    const [now] = useState(() => new Date());

    if (briefing.error) return <ErrorBox>{briefing.error}</ErrorBox>;

    const b = briefing.data;
    const hello = now.getHours() >= 18 ? "Bonsoir" : "Bonjour";
    const last = b?.lastSession;

    return (
        <>
            <header className="mb-6">
                <div className="flex items-center gap-3">
                    {b && <Avatar photo={b.photo} name={b.name} />}
                    <div>
                        <p className="text-sm text-slate-500 capitalize dark:text-slate-400">{now.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long" })}</p>
                        <h1 className="text-2xl font-semibold tracking-tight">
                            {hello}
                            {b?.name ? ` ${b.name}` : ""}
                        </h1>
                    </div>
                </div>
                {b?.headline && <p className="mt-2 leading-relaxed text-slate-700 dark:text-slate-300">{b.headline}</p>}
            </header>

            {b && (
                <>
                    <HomeTiles briefing={b} panel={panel} onToggle={(p) => setPanel((cur) => (cur === p ? null : p))} />
                    {panel === "load" && (
                        <div className="mt-3">
                            <LoadPanel comparison={b.comparison} />
                        </div>
                    )}
                    {panel === "readiness" && b.readiness && (
                        <div className="mt-3">
                            <ReadinessPanel readiness={b.readiness} />
                        </div>
                    )}
                </>
            )}

            {last && (
                <Card title="Dernier fractionné" className="mt-6" action={<VerdictPill verdict={last.verdict} label={last.verdictLabel} />}>
                    <Link to={`/activites/${last.id}`} className="group block">
                        <div className="font-semibold group-hover:text-accent">{last.title}</div>
                        <div className="text-sm text-slate-500 dark:text-slate-400">{formatDate(last.startTimeLocal, { dateStyle: "full", timeStyle: "short" })}</div>
                        <p className="mt-3 leading-relaxed text-slate-700 dark:text-slate-300">{last.summary}</p>
                        <span className="mt-3 inline-flex items-center gap-1 text-sm font-medium text-accent">
                            Lire l'analyse complète <ArrowRight className="size-4" />
                        </span>
                    </Link>
                </Card>
            )}

            {summary.data && (
                <div className="mt-6">
                    <WeeklyChart weeks={summary.data.weeks} />
                </div>
            )}

            <Card
                title="Activités récentes"
                className="mt-6"
                action={
                    <Link to="/activites" className="text-sm font-medium text-accent hover:underline">
                        Tout voir
                    </Link>
                }
            >
                {recent.data?.items.length ? (
                    <ul className="-mx-2 divide-y divide-slate-100 dark:divide-slate-800">
                        {recent.data.items.map((a) => (
                            <ActivityRow key={a.id} activity={a} />
                        ))}
                    </ul>
                ) : (
                    !recent.loading && <EmptyState>Pas encore d'activité. Lance une synchronisation.</EmptyState>
                )}
            </Card>
        </>
    );
}
