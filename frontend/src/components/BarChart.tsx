import { useState } from "react";

export interface Bar {
    key: string;
    label: string;
    /** Top of the bar. For stacked bars, the sum of segments. */
    value: number | null;
    /** Bottom of the bar, for floating range bars (e.g. Body Battery low → high). */
    base?: number | null;
    /** Stacked parts, bottom to top, matching the `series` prop. */
    segments?: (number | null)[];
    /** Text shown on hover, e.g. "12,4 km" */
    display: string;
}

export interface Series {
    label: string;
    className: string;
}

interface Props {
    bars: Bar[];
    ariaLabel: string;
    height?: number;
    /** For stacked bars: one entry per segment. A legend is shown. */
    series?: Series[];
}

/**
 * Bar chart: single series (no legend, the card title names it), floating ranges, or stacked
 * segments with a legend. Hover shows the value above the plot.
 */
export function BarChart({ bars, height = 160, ariaLabel, series }: Props) {
    const [hovered, setHovered] = useState<string | null>(null);
    const max = Math.max(1, ...bars.map((b) => b.value ?? 0));
    const active = bars.find((b) => b.key === hovered);
    const labelEvery = Math.ceil(bars.length / 8);
    const scale = (v: number) => (v / max) * 100;

    return (
        <figure aria-label={ariaLabel}>
            {series && (
                <div className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600 dark:text-slate-300">
                    {series.map((s) => (
                        <span key={s.label} className="inline-flex items-center gap-1.5">
                            <span className={`size-2.5 rounded-sm ${s.className}`} />
                            {s.label}
                        </span>
                    ))}
                </div>
            )}
            <div className="mb-2 h-5 truncate text-sm tabular-nums text-slate-600 dark:text-slate-300">
                {active && (
                    <>
                        <span className="text-slate-500 dark:text-slate-400">{active.label} · </span>
                        <span className="font-semibold">{active.display}</span>
                    </>
                )}
            </div>
            <div className={`flex items-end border-b border-slate-200 dark:border-slate-800 ${bars.length > 60 ? "gap-px" : "gap-0.5"}`} style={{ height }}>
                {bars.map((bar) => {
                    const top = bar.value === null ? 0 : scale(bar.value);
                    const bottom = bar.base ? scale(bar.base) : 0;
                    const dimmed = hovered && hovered !== bar.key ? "opacity-40" : "";
                    return (
                        <div
                            key={bar.key}
                            className="relative h-full min-w-0 flex-1 cursor-default"
                            onMouseEnter={() => setHovered(bar.key)}
                            onMouseLeave={() => setHovered(null)}
                            onTouchStart={() => setHovered(bar.key)}
                            title={`${bar.label} : ${bar.display}`}
                        >
                            {bar.segments && series ? (
                                <div className={`absolute inset-x-0 bottom-0 flex flex-col-reverse gap-[2px] transition-opacity ${dimmed}`} style={{ height: `${top}%` }}>
                                    {bar.segments.map((seg, i) =>
                                        seg ? (
                                            <div
                                                key={i}
                                                className={`w-full ${series[i].className} ${i === bar.segments!.length - 1 ? "rounded-t" : ""}`}
                                                style={{ flexGrow: seg, flexBasis: 0, minHeight: 1 }}
                                            />
                                        ) : null,
                                    )}
                                </div>
                            ) : (
                                <div
                                    className={`absolute inset-x-0 rounded-t bg-accent transition-opacity ${bar.base ? "rounded-b" : ""} ${dimmed}`}
                                    style={{ bottom: `${bottom}%`, height: `${Math.max(top - bottom, bar.value ? 1 : 0)}%` }}
                                />
                            )}
                        </div>
                    );
                })}
            </div>
            <div className={`mt-1.5 flex text-[11px] text-slate-500 dark:text-slate-400 ${bars.length > 60 ? "gap-px" : "gap-0.5"}`}>
                {bars.map((bar, i) => (
                    <div key={bar.key} className="flex min-w-0 flex-1 justify-center overflow-visible whitespace-nowrap">
                        {/* Sparse labels, anchored on the last bar, to avoid collisions */}
                        {(bars.length - 1 - i) % labelEvery === 0 ? bar.label : ""}
                    </div>
                ))}
            </div>
            {/* A table ignores the 1px width of sr-only: the wrapper carries it */}
            <div className="sr-only">
                <table>
                    <tbody>
                        {bars.map((bar) => (
                            <tr key={bar.key}>
                                <th>{bar.label}</th>
                                <td>{bar.display}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </figure>
    );
}
