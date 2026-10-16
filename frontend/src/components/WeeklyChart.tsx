import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { Card } from "./ui";
import type { WeekSummary } from "../lib/api";
import { formatNumber } from "../lib/format";

const H = 150;
const PAD = { top: 18, right: 12, bottom: 24, left: 12 };

const km = (m: number) => (m / 1000).toLocaleString("fr-FR", { maximumFractionDigits: 1 });

function hoursMinutes(seconds: number): string {
    const m = Math.round(seconds / 60);
    return m >= 60 ? `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")}` : `${m} min`;
}

const weekLabel = (week: string) => new Date(`${week}T12:00:00`).toLocaleDateString("fr-FR", { day: "numeric", month: "short" });

/**
 * Weekly running volume, like the Strava app: the selected week in big numbers, a line over the last weeks
 * below; tap (or arrow keys) to pick another week.
 */
export function WeeklyChart({ weeks }: { weeks: WeekSummary[] }) {
    const [selected, setSelected] = useState(weeks.length - 1);
    // Drawn at the real width of the card, so labels keep their size on a phone
    const box = useRef<HTMLDivElement>(null);
    const [W, setW] = useState(600);
    useEffect(() => {
        const el = box.current;
        if (!el) return;
        const observer = new ResizeObserver(([entry]) => setW(Math.max(Math.round(entry.contentRect.width), 240)));
        observer.observe(el);
        return () => observer.disconnect();
    }, []);

    const week = weeks[selected];
    if (!week) return null;

    const max = Math.max(...weeks.map((w) => w.distance), 1000);
    // Round the top of the scale to 10 km so the gridline label reads clean
    const top = Math.ceil(max / 10_000) * 10_000;
    const step = (W - PAD.left - PAD.right) / Math.max(weeks.length - 1, 1);
    const x = (i: number) => PAD.left + i * step;
    const y = (d: number) => PAD.top + (1 - d / top) * (H - PAD.top - PAD.bottom);
    const line = weeks.map((w, i) => `${i ? "L" : "M"}${x(i)},${y(w.distance)}`).join(" ");
    const area = `${line} L${x(weeks.length - 1)},${y(0)} L${x(0)},${y(0)} Z`;

    // Month name under the first week of each month, skipped when it would overlap the previous one
    const months = weeks
        .map((w, i) => ({ i, month: w.week.slice(0, 7) }))
        .filter((m, k, all) => k === 0 || m.month !== all[k - 1].month)
        .filter((m, k, all) => k === 0 || x(m.i) - x(all[k - 1].i) >= 34)
        .map(({ i, month }) => ({ i, label: new Date(`${month}-15T12:00:00`).toLocaleDateString("fr-FR", { month: "short" }).replace(".", "") }));

    const onKey = (e: KeyboardEvent) => {
        if (e.key === "ArrowLeft") setSelected((s) => Math.max(0, s - 1));
        else if (e.key === "ArrowRight") setSelected((s) => Math.min(weeks.length - 1, s + 1));
        else return;
        e.preventDefault();
    };

    const isCurrent = selected === weeks.length - 1;
    return (
        <Card title="Course à pied, 6 derniers mois">
            <div className="mb-2 text-sm font-semibold">{isCurrent ? "Cette semaine" : `Semaine du ${weekLabel(week.week)}`}</div>
            <dl className="grid grid-cols-3 gap-3">
                {[
                    ["Distance", `${km(week.distance)} km`],
                    ["Temps", hoursMinutes(week.duration)],
                    ["Dénivelé", formatNumber(week.elevation, "m")],
                ].map(([label, value]) => (
                    <div key={label}>
                        <dt className="text-xs text-slate-500 dark:text-slate-400">{label}</dt>
                        <dd className="text-xl font-semibold tabular-nums sm:text-2xl">{value}</dd>
                    </div>
                ))}
            </dl>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                {week.count} sortie{week.count > 1 ? "s" : ""} · touche un point pour voir une autre semaine
            </p>

            <div ref={box} className="mt-4">
                <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} className="block touch-manipulation select-none focus:outline-none focus-visible:rounded-lg focus-visible:outline-2 focus-visible:outline-accent" role="group" aria-label={`Distance par semaine. Semaine sélectionnée : du ${weekLabel(week.week)}, ${km(week.distance)} km. Flèches gauche et droite pour changer de semaine.`} tabIndex={0} onKeyDown={onKey}>
                    {/* Recessive scale: one gridline at the top value, the baseline */}
                    <line x1={PAD.left} x2={W - PAD.right} y1={y(top)} y2={y(top)} className="stroke-slate-200 dark:stroke-slate-800" strokeDasharray="3 4" />
                    <text x={W - PAD.right} y={y(top) - 5} textAnchor="end" className="fill-slate-500 text-[11px] dark:fill-slate-400">
                        {top / 1000} km
                    </text>
                    <line x1={PAD.left} x2={W - PAD.right} y1={y(0)} y2={y(0)} className="stroke-slate-200 dark:stroke-slate-800" />

                    <path d={area} className="fill-accent/10" />
                    <path d={line} className="fill-none stroke-accent" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
                    <line x1={x(selected)} x2={x(selected)} y1={PAD.top - 6} y2={y(0)} className="stroke-accent/40" strokeWidth={1} />

                    {weeks.map((w, i) => (
                        <g key={w.week}>
                            <circle cx={x(i)} cy={y(w.distance)} r={i === selected ? 5.5 : 4} className={i === selected ? "fill-accent stroke-white dark:stroke-slate-900" : "fill-white stroke-accent dark:fill-slate-900"} strokeWidth={i === selected ? 2 : 1.5} />
                            {/* Hit target: the whole column, much bigger than the dot */}
                            <rect x={x(i) - step / 2} y={0} width={step} height={H} fill="transparent" className="cursor-pointer" onClick={() => setSelected(i)} onMouseEnter={() => setSelected(i)}>
                                <title>{`Semaine du ${weekLabel(w.week)} : ${km(w.distance)} km`}</title>
                            </rect>
                        </g>
                    ))}

                    {months.map((m) => (
                        <text key={m.i} x={x(m.i)} y={H - 6} textAnchor={m.i === 0 ? "start" : "middle"} className="fill-slate-500 text-[11px] dark:fill-slate-400">
                            {m.label}
                        </text>
                    ))}
                </svg>
            </div>

            {/* A table ignores the 1px width of sr-only: the wrapper carries it */}
            <div className="sr-only">
                <table>
                    <caption>Distance de course par semaine</caption>
                    <thead>
                        <tr>
                            <th>Semaine du</th>
                            <th>Distance</th>
                            <th>Temps</th>
                            <th>Dénivelé</th>
                        </tr>
                    </thead>
                    <tbody>
                        {weeks.map((w) => (
                            <tr key={w.week}>
                                <td>{weekLabel(w.week)}</td>
                                <td>{km(w.distance)} km</td>
                                <td>{hoursMinutes(w.duration)}</td>
                                <td>{Math.round(w.elevation)} m</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </Card>
    );
}
