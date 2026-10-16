import { useEffect, useRef, useState } from "react";
import { Card } from "./ui";

export interface BatteryPoint {
    key: string;
    label: string;
    /** Highest level of the day, reached at wake-up */
    high: number | null;
    /** Lowest level of the day, usually in the evening */
    low: number | null;
}

const H = 170;
const PAD = { top: 10, right: 30, bottom: 22, left: 4 };

const round = (v: number | null) => (v === null ? "–" : String(Math.round(v)));

/**
 * Body Battery on its true 0-100 scale: one bar per day from the lowest level to the wake-up level
 * (the dot), so a short bar means a day that drained little and a high dot a good recharge.
 */
export function BodyBatteryCard({ points, title }: { points: BatteryPoint[]; title: string }) {
    const box = useRef<HTMLDivElement>(null);
    const [W, setW] = useState(600);
    const [selected, setSelected] = useState<number | null>(null);
    useEffect(() => {
        const el = box.current;
        if (!el) return;
        const observer = new ResizeObserver(([entry]) => setW(Math.max(Math.round(entry.contentRect.width), 240)));
        observer.observe(el);
        return () => observer.disconnect();
    }, []);

    const withData = points.filter((p) => p.high !== null);
    const last = withData.at(-1);
    if (!last) return null;
    const shown = selected !== null && points[selected]?.high !== null ? points[selected] : last;

    const step = (W - PAD.left - PAD.right) / points.length;
    const barW = Math.max(Math.min(step * 0.6, 14), 2);
    const x = (i: number) => PAD.left + step * (i + 0.5);
    const y = (v: number) => PAD.top + (1 - v / 100) * (H - PAD.top - PAD.bottom);
    const labelEvery = Math.ceil(points.length / Math.max(Math.floor(W / 70), 1));

    return (
        <Card title={title} className="min-w-0 lg:col-span-2">
            <div className="mb-1 text-sm font-semibold">{shown === last ? "Dernière nuit" : shown.label}</div>
            <dl className="grid grid-cols-2 gap-3">
                <div>
                    <dt className="text-xs text-slate-500 dark:text-slate-400">Max (au réveil)</dt>
                    <dd className="text-2xl font-semibold tabular-nums">{round(shown.high)}</dd>
                </div>
                <div>
                    <dt className="text-xs text-slate-500 dark:text-slate-400">Min</dt>
                    <dd className="text-2xl font-semibold tabular-nums">{round(shown.low)}</dd>
                </div>
            </dl>

            <div ref={box} className="mt-4 min-w-0 overflow-hidden">
                <svg width={W} height={H} viewBox={`0 0 ${W} ${H}`} className="block select-none" role="img" aria-label={`Body Battery par jour, du minimum au niveau du réveil. Dernier réveil : ${round(last.high)} sur 100.`} onMouseLeave={() => setSelected(null)}>
                    {/* Fixed 0-100 scale: a few recessive gridlines */}
                    {[0, 25, 50, 75, 100].map((v) => (
                        <g key={v}>
                            <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)} className={v === 0 ? "stroke-slate-200 dark:stroke-slate-800" : "stroke-slate-100 dark:stroke-slate-800/60"} strokeDasharray={v === 0 ? undefined : "3 4"} />
                            {v > 0 && (
                                <text x={W - PAD.right + 6} y={y(v) + 4} className="fill-slate-500 text-[11px] dark:fill-slate-400">
                                    {v}
                                </text>
                            )}
                        </g>
                    ))}

                    {points.map((p, i) => {
                        if (p.high === null) return null;
                        const low = p.low ?? p.high;
                        const active = selected === i;
                        return (
                            <g key={p.key} className={selected !== null && !active ? "opacity-40" : ""}>
                                <rect x={x(i) - barW / 2} y={y(p.high)} width={barW} height={Math.max(y(low) - y(p.high), 2)} rx={Math.min(barW / 2, 4)} className="fill-accent/35" />
                                <circle cx={x(i)} cy={y(p.high)} r={Math.max(Math.min(barW / 2 + 1, 5), 3)} className="fill-accent stroke-white dark:stroke-slate-900" strokeWidth={1.5} />
                            </g>
                        );
                    })}

                    {points.map((p, i) => (
                        <rect key={p.key} x={x(i) - step / 2} y={0} width={step} height={H} fill="transparent" onMouseEnter={() => setSelected(i)} onClick={() => setSelected(i)}>
                            <title>{`${p.label} : max ${round(p.high)}, min ${round(p.low)}`}</title>
                        </rect>
                    ))}

                    {points.map((p, i) =>
                        i % labelEvery === 0 ? (
                            <text key={p.key} x={i === 0 ? PAD.left : x(i)} y={H - 5} textAnchor={i === 0 ? "start" : "middle"} className="fill-slate-500 text-[11px] dark:fill-slate-400">
                                {p.label}
                            </text>
                        ) : null,
                    )}
                </svg>
            </div>
            <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">Chaque barre va du min au max de la journée (le point). Touche une barre pour voir le jour.</p>
        </Card>
    );
}
