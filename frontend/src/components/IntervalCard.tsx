import { Card } from "./ui";
import type { IntervalAnalysis, LapRole, RepGroup } from "../lib/intervals";
import { formatDistance, formatDuration, formatNumber, formatPace } from "../lib/format";

export const roleLabels: Record<LapRole, string> = {
    warmup: "Échauffement",
    work: "Effort",
    recovery: "Récup",
    cooldown: "Retour au calme",
    other: "–",
};

function recoveryText(g: RepGroup): string {
    if (!g.recovery) return "–";
    return `${formatDuration(g.recovery.duration)} à ${formatPace(g.recovery.speed)}`;
}

function Group({ group }: { group: RepGroup }) {
    const spread = Math.round(1000 / group.worstSpeed - 1000 / group.bestSpeed);
    return (
        <div>
            <h3 className="mb-3 text-lg font-semibold">
                {group.reps.length} × {group.label}
                {group.recovery && <span className="font-normal text-slate-500 dark:text-slate-400"> (r : {group.recovery.label})</span>}
            </h3>
            <dl className="mb-4 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
                {[
                    ["Allure moyenne", formatPace(group.avgSpeed)],
                    [group.timeBased ? "Distance moyenne" : "Temps moyen", group.timeBased ? formatDistance(group.avgDistance) : formatDuration(group.avgDuration)],
                    ["FC moyenne", formatNumber(group.avgHr, "bpm")],
                    ["Récup moyenne", recoveryText(group)],
                ].map(([label, value]) => (
                    <div key={label}>
                        <dt className="text-sm text-slate-500 dark:text-slate-400">{label}</dt>
                        <dd className="font-semibold tabular-nums">{value}</dd>
                    </div>
                ))}
            </dl>
            <div className="-mx-5 overflow-x-auto px-5">
                <table className="w-full min-w-[26rem] text-sm tabular-nums">
                    <thead>
                        <tr className="text-left text-slate-500 dark:text-slate-400">
                            <th className="py-1.5 font-medium">Rép.</th>
                            <th className="py-1.5 font-medium">{group.timeBased ? "Distance" : "Temps"}</th>
                            <th className="py-1.5 font-medium">Allure</th>
                            <th className="py-1.5 font-medium">Écart</th>
                            <th className="py-1.5 font-medium">FC moy.</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                        {group.reps.map((r) => {
                            const speed = r.lap.distance! / r.lap.duration!;
                            // Seconds per km versus the group average: negative = faster
                            const delta = Math.round(1000 / speed - 1000 / group.avgSpeed);
                            return (
                                <tr key={r.rep}>
                                    <td className="py-1.5">{r.rep}</td>
                                    <td className="py-1.5">{group.timeBased ? formatDistance(r.lap.distance) : formatDuration(r.lap.duration)}</td>
                                    <td className="py-1.5 font-medium">{formatPace(speed)}</td>
                                    <td className={`py-1.5 ${delta < 0 ? "text-emerald-700 dark:text-emerald-400" : delta > 0 ? "text-amber-700 dark:text-amber-400" : ""}`}>{delta === 0 ? "=" : `${delta > 0 ? "+" : "−"}${Math.abs(delta)} s/km`}</td>
                                    <td className="py-1.5">{formatNumber(r.lap.averageHr)}</td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            </div>
            {group.reps.length > 1 && <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">Régularité : {spread} s/km entre la plus rapide et la plus lente.</p>}
        </div>
    );
}

export function IntervalCard({ analysis }: { analysis: IntervalAnalysis }) {
    return (
        <Card title={`Fractionné${analysis.structured ? " (séance programmée)" : ""}`}>
            <div className="space-y-8">
                {analysis.groups.map((g, i) => (
                    <Group key={i} group={g} />
                ))}
            </div>
        </Card>
    );
}
