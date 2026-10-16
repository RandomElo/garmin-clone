import { useState } from "react";
import { ArrowDownRight, ArrowUpRight, ChevronDown } from "lucide-react";
import { Card } from "./ui";
import { VerdictPill } from "./Verdict";
import type { Contribution, SessionAnalysis } from "../lib/api";

const fr = (v: number, digits = 0) => Math.abs(v).toLocaleString("fr-FR", { maximumFractionDigits: digits });

/** The few numbers that sum the session up, each with a plain-words caption. */
function keyFigures(a: SessionAnalysis): [string, string, string][] {
    const figures: [string, string, string][] = [];
    if (a.paceDeltaSecPerKm !== null) {
        const d = a.paceDeltaSecPerKm;
        figures.push(["Allure", d === 0 ? "=" : `${d < 0 ? "−" : "+"}${fr(d)} s/km`, d === 0 ? "comme d'habitude" : d < 0 ? "plus vite que d'habitude" : "plus lent que d'habitude"]);
    }
    if (a.hrDelta !== null) {
        const d = Math.round(a.hrDelta);
        figures.push(["Fréquence cardiaque", d === 0 ? "=" : `${d < 0 ? "−" : "+"}${fr(d)} bpm`, d === 0 ? "comme d'habitude" : d < 0 ? "plus basse que d'habitude" : "plus haute que d'habitude"]);
    }
    if (a.fadePct !== null) {
        const d = a.fadePct;
        figures.push(["Fin de série", Math.abs(d) < 1.5 ? "Stable" : `${d < 0 ? "−" : "+"}${fr(d, 1)} %`, Math.abs(d) < 1.5 ? "allure tenue jusqu'au bout" : d < 0 ? "plus lente que le début" : "plus rapide que le début"]);
    }
    if (a.hrRecovery) figures.push(["Récup cardiaque", `−${a.hrRecovery.avg} bpm`, "en 1 min de récup"]);
    return figures;
}

/** Solid pill: dark green or dark orange with white text (≥ 5:1 contrast), plus an arrow so color is not the only cue. */
function ContributionChip({ c }: { c: Contribution }) {
    const slower = c.effectPct < 0;
    const secs = Math.abs(c.effectSecPerKm);
    const Icon = slower ? ArrowDownRight : ArrowUpRight;
    return (
        <li
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-sm font-medium text-white ${slower ? "bg-orange-700" : "bg-emerald-700"}`}
            title={`${c.detail} · ${c.source === "perso" ? "estimé sur ton historique" : "estimé d'après les études"}`}
        >
            <Icon className="size-3.5" aria-hidden />
            {c.label}
            {secs > 0 && (
                <span className="font-bold tabular-nums">
                    {slower ? "+" : "−"}
                    {secs} s/km
                </span>
            )}
        </li>
    );
}

export function SessionAnalysisCard({ analysis }: { analysis: SessionAnalysis }) {
    const [showTrends, setShowTrends] = useState(false);
    const figures = keyFigures(analysis);
    const trends = analysis.correlations.filter((c) => c.strength !== "aucune");

    return (
        <Card title="Analyse de la séance" action={<VerdictPill verdict={analysis.verdict} label={analysis.verdictLabel} />}>
            {figures.length > 0 && (
                <dl className="mb-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
                    {figures.map(([label, value, caption]) => (
                        <div key={label} className="rounded-xl bg-slate-50 p-3 dark:bg-slate-800/60">
                            <dt className="text-xs text-slate-500 dark:text-slate-400">{label}</dt>
                            <dd className="text-xl font-semibold tabular-nums">{value}</dd>
                            <dd className="text-xs text-slate-500 dark:text-slate-400">{caption}</dd>
                        </div>
                    ))}
                </dl>
            )}

            <div className="space-y-3 leading-relaxed text-slate-700 dark:text-slate-300">
                {analysis.paragraphs.map((p, i) => (
                    <p key={i}>{p}</p>
                ))}
            </div>

            {analysis.contributions.length > 0 && (
                <div className="mt-5">
                    <h3 className="text-sm font-semibold text-slate-500 dark:text-slate-400">Ce qui a joué sur l'allure</h3>
                    <p className="mb-2 text-xs text-slate-500 dark:text-slate-400">En vert ce qui t'a fait gagner du temps, en orange ce qui t'en a fait perdre.</p>
                    <ul className="flex flex-wrap gap-2">
                        {analysis.contributions.map((c) => (
                            <ContributionChip key={c.key} c={c} />
                        ))}
                    </ul>
                </div>
            )}

            {trends.length > 0 && (
                <div className="mt-5 border-t border-slate-100 pt-4 dark:border-slate-800">
                    <button type="button" onClick={() => setShowTrends((v) => !v)} aria-expanded={showTrends} className="flex w-full items-center justify-between gap-2 text-left text-sm font-medium text-accent">
                        Ce qui influence tes séances, d'après tes {analysis.sampleSize} fractionnés
                        <ChevronDown className={`size-4 shrink-0 transition-transform ${showTrends ? "rotate-180" : ""}`} />
                    </button>
                    {showTrends && (
                        <ul className="mt-3 space-y-2 text-sm text-slate-700 dark:text-slate-300">
                            {trends.map((c) => (
                                <li key={c.key}>{c.text}</li>
                            ))}
                        </ul>
                    )}
                </div>
            )}
        </Card>
    );
}
