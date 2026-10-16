import { useState } from "react";
import { Card } from "./ui";
import { LineChart } from "./LineChart";
import type { LoadSummary } from "../lib/api";
import { formatScore } from "../lib/format";

const dayLabel = (date: string, long: boolean) =>
    new Date(`${date}T12:00:00`).toLocaleDateString("fr-FR", long ? { month: "short", year: "2-digit" } : { day: "numeric", month: "short" });

/** Load over 4 weeks and fatigue: one chart each (different meanings, never a shared axis), hover shared between them. */
export function LoadCharts({ series }: { series: LoadSummary["series"] }) {
    const [hoverX, setHoverX] = useState<number | null>(null);
    const long = series.length > 120;
    const xLabel = (x: number) => (series[Math.round(x)] ? dayLabel(series[Math.round(x)].date, long) : "");
    const line = (pick: (r: LoadSummary["series"][number]) => number | null) => series.map((r, i) => ({ x: i, y: pick(r) }));
    return (
        <div className="grid gap-6">
            <Card title="Charge des 4 dernières semaines (sur 20)">
                <LineChart ariaLabel="Charge sur 4 semaines, sur 20" points={line((r) => r.month)} formatX={xLabel} formatY={formatScore} domain={[0, 20]} area hoverX={hoverX} onHoverX={setHoverX} />
            </Card>
            <Card title="Fatigue (sur 10)">
                <LineChart ariaLabel="Fatigue sur 10" points={line((r) => r.fatigue)} formatX={xLabel} formatY={formatScore} domain={[0, 10]} hoverX={hoverX} onHoverX={setHoverX} />
            </Card>
        </div>
    );
}
