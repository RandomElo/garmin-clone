import { Bike, ChevronRight, Dumbbell, Footprints, Mountain, PersonStanding, Trophy } from "lucide-react";
import { Link } from "react-router-dom";
import type { Activity } from "../lib/api";
import { activityTypeLabel, formatDate, formatScore, formatDistance, formatDuration, formatPace, formatSpeed, isRunning } from "../lib/format";

function ActivityIcon({ type, category }: { type: string; category: Activity["category"] }) {
    if (category === "competition") return <Trophy className="size-5" />;
    if (category === "strength") return <Dumbbell className="size-5" />;
    if (type.includes("trail")) return <Mountain className="size-5" />;
    if (isRunning(type)) return <Footprints className="size-5" />;
    if (type.includes("cycl") || type.includes("bik")) return <Bike className="size-5" />;
    return <PersonStanding className="size-5" />;
}

export function ActivityRow({ activity: a }: { activity: Activity }) {
    return (
        <li>
            <Link to={`/activites/${a.id}`} className="flex items-center gap-3 rounded-xl px-2 py-3 transition hover:bg-slate-50 dark:hover:bg-slate-800/60">
                <div className="grid size-10 shrink-0 place-items-center rounded-full bg-accent/10 text-accent">
                    <ActivityIcon type={a.type} category={a.category} />
                </div>
                <div className="min-w-0 flex-1">
                    <div className="line-clamp-2 font-medium break-words">{a.intervalTitle ?? a.name}</div>
                    <div className="text-xs text-slate-500 dark:text-slate-400">
                        {activityTypeLabel(a.type)} · {formatDate(a.startTimeLocal, { weekday: "short", day: "numeric", month: "short" })}
                        {a.effort !== null && ` · effort ${formatScore(a.effort)}`}
                        {a.load && ` · charge ${formatScore(a.load.charge)}`}
                    </div>
                </div>
                <div className="text-right text-sm tabular-nums">
                    <div className="font-semibold">{formatDistance(a.distance)}</div>
                    <div className="text-xs text-slate-500 dark:text-slate-400">
                        {formatDuration(a.duration)} · {isRunning(a.type) ? formatPace(a.averageSpeed) : formatSpeed(a.averageSpeed)}
                    </div>
                </div>
                <ChevronRight className="size-4 shrink-0 text-slate-400" />
            </Link>
        </li>
    );
}
