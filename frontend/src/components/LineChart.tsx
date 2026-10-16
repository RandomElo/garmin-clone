import { useEffect, useState } from "react";

export interface Point {
    x: number;
    y: number | null;
}

interface Props {
    points: Point[];
    ariaLabel: string;
    formatX: (x: number) => string;
    formatY: (y: number) => string;
    height?: number;
    /** Fill the area under the line (elevation profile). */
    area?: boolean;
    /** Fixed y-axis (e.g. [0, 20] for a score on 20) instead of zooming on the data */
    domain?: [number, number];
    /** Shared hover: charts with the same x axis show the same point (x of the hovered point, null when none) */
    hoverX?: number | null;
    onHoverX?: (x: number | null) => void;
}

const PAD = { top: 8, right: 8, bottom: 22, left: 48 };

function niceTicks(min: number, max: number, count: number): number[] {
    if (min === max) return [min];
    const step = (max - min) / (count - 1);
    return Array.from({ length: count }, (_, i) => min + step * i);
}

/** Single-series line chart with a hover crosshair. One y-axis only. */
export function LineChart({ points, ariaLabel, formatX, formatY, height = 180, area = false, domain, hoverX, onHoverX }: Props) {
    // Callback ref: the element changes between the "no data" and chart renders, the observer must follow it
    const [el, setEl] = useState<HTMLElement | null>(null);
    const [width, setWidth] = useState(600);
    const [ownHover, setOwnHover] = useState<number | null>(null);
    // Controlled when the parent shares the hover between charts, local otherwise
    const shared = onHoverX !== undefined;
    const hovered = shared ? (hoverX ?? null) : ownHover;
    const setHovered = (x: number | null) => (shared ? onHoverX(x) : setOwnHover(x));

    useEffect(() => {
        if (!el) return;
        const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
        observer.observe(el);
        return () => observer.disconnect();
    }, [el]);

    const valid = points.filter((p): p is { x: number; y: number } => p.y !== null);
    if (valid.length < 2)
        return (
            <div ref={setEl} className="text-sm text-slate-500 dark:text-slate-400">
                Pas de données.
            </div>
        );

    // x range from all points, not only valid ones: charts sharing an x axis stay aligned, gaps show as gaps
    const xMin = points[0].x;
    const xMax = points[points.length - 1].x;
    let yMin = domain ? domain[0] : Math.min(...valid.map((p) => p.y));
    let yMax = domain ? domain[1] : Math.max(...valid.map((p) => p.y));
    const margin = domain ? 0 : (yMax - yMin) * 0.08 || 1;
    yMin -= margin;
    yMax += margin;

    const innerW = Math.max(width - PAD.left - PAD.right, 10);
    const innerH = height - PAD.top - PAD.bottom;
    const sx = (x: number) => PAD.left + ((x - xMin) / (xMax - xMin || 1)) * innerW;
    const sy = (y: number) => PAD.top + (1 - (y - yMin) / (yMax - yMin)) * innerH;

    // Break the line where values are missing instead of interpolating
    let d = "";
    let pen = false;
    for (const p of points) {
        if (p.y === null) {
            pen = false;
            continue;
        }
        d += `${pen ? "L" : "M"}${sx(p.x).toFixed(1)},${sy(p.y).toFixed(1)}`;
        pen = true;
    }
    const areaPath = area ? `${d}L${sx(valid[valid.length - 1].x)},${PAD.top + innerH}L${sx(valid[0].x)},${PAD.top + innerH}Z` : null;

    // The hovered x may have no value on this chart (gap): no point shown then
    const active = hovered === null ? null : (valid.find((p) => p.x === hovered) ?? null);
    const onMove = (clientX: number) => {
        const rect = el?.getBoundingClientRect();
        if (!rect) return;
        const x = xMin + ((clientX - rect.left - PAD.left) / innerW) * (xMax - xMin);
        let best = 0;
        for (let i = 1; i < valid.length; i++) if (Math.abs(valid[i].x - x) < Math.abs(valid[best].x - x)) best = i;
        setHovered(valid[best].x);
    };

    return (
        <figure ref={setEl} aria-label={ariaLabel} className="relative select-none">
            <svg width={width} height={height} onMouseMove={(e) => onMove(e.clientX)} onMouseLeave={() => setHovered(null)} onTouchMove={(e) => onMove(e.touches[0].clientX)} onTouchEnd={() => setHovered(null)}>
                {niceTicks(yMin + margin, yMax - margin, domain ? 5 : 4).map((t) => (
                    <g key={t}>
                        <line x1={PAD.left} x2={PAD.left + innerW} y1={sy(t)} y2={sy(t)} className="stroke-slate-100 dark:stroke-slate-800" />
                        <text x={PAD.left - 6} y={sy(t)} textAnchor="end" dominantBaseline="middle" className="fill-slate-500 text-[10px] tabular-nums dark:fill-slate-400">
                            {formatY(t)}
                        </text>
                    </g>
                ))}
                {niceTicks(xMin, xMax, Math.max(2, Math.min(6, Math.floor(innerW / 90)))).map((t) => (
                    <text key={t} x={sx(t)} y={height - 6} textAnchor={t === xMin ? "start" : t === xMax ? "end" : "middle"} className="fill-slate-500 text-[10px] tabular-nums dark:fill-slate-400">
                        {formatX(t)}
                    </text>
                ))}
                {areaPath && <path d={areaPath} className="fill-accent/15" />}
                <path d={d} fill="none" strokeWidth={2} strokeLinejoin="round" className="stroke-accent" />
                {active && (
                    <>
                        <line x1={sx(active.x)} x2={sx(active.x)} y1={PAD.top} y2={PAD.top + innerH} className="stroke-slate-400" strokeDasharray="3 3" />
                        <circle cx={sx(active.x)} cy={sy(active.y)} r={4} strokeWidth={2} className="fill-accent stroke-white dark:stroke-slate-900" />
                    </>
                )}
            </svg>
            {active && (
                <div className="pointer-events-none absolute top-0 rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs shadow-sm tabular-nums dark:border-slate-700 dark:bg-slate-800" style={{ left: Math.min(sx(active.x) + 8, width - 110) }}>
                    <div className="font-semibold">{formatY(active.y)}</div>
                    <div className="text-slate-500 dark:text-slate-400">{formatX(active.x)}</div>
                </div>
            )}
        </figure>
    );
}

/** Centered moving average, to tame noisy GPS speed. */
export function smooth(points: Point[], window = 5): Point[] {
    const half = Math.floor(window / 2);
    return points.map((p, i) => {
        if (p.y === null) return p;
        const slice = points.slice(Math.max(0, i - half), i + half + 1).filter((q) => q.y !== null) as { x: number; y: number }[];
        return { x: p.x, y: slice.reduce((s, q) => s + q.y, 0) / slice.length };
    });
}
