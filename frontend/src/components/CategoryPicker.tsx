import { useState } from "react";
import { ChevronDown } from "lucide-react";
import { api, type ActivityCategory } from "../lib/api";
import { categoryLabels } from "../lib/categories";


/**
 * Kind of a session, chosen by hand. "Automatique" goes back to the detection (laps, distance).
 * Saved at once: lists, filters and load use it right away.
 */
export function CategoryPicker({ id, category, manual }: { id: number; category: ActivityCategory | null; manual: boolean }) {
    const [value, setValue] = useState<ActivityCategory | null>(category);
    const [isManual, setIsManual] = useState(manual);
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const save = async (next: ActivityCategory | null) => {
        setSaving(true);
        try {
            const res = await api.setCategory(id, next);
            setValue(res.category);
            setIsManual(res.manual);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Enregistrement impossible");
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="flex flex-wrap items-center gap-2 text-sm">
            <label htmlFor={`category-${id}`} className="text-slate-500 dark:text-slate-400">
                Type
            </label>
            <span className="relative">
                <select id={`category-${id}`} disabled={saving} value={isManual ? (value ?? "") : "auto"} onChange={(e) => void save(e.target.value === "auto" ? null : (e.target.value as ActivityCategory))} className="appearance-none rounded-lg border border-slate-200 bg-white py-1 pr-8 pl-2.5 font-medium disabled:opacity-60 dark:border-slate-700 dark:bg-slate-900">
                    <option value="auto">Automatique{!isManual && value ? ` (${categoryLabels[value]})` : ""}</option>
                    {(Object.keys(categoryLabels) as ActivityCategory[]).map((c) => (
                        <option key={c} value={c}>
                            {categoryLabels[c]}
                        </option>
                    ))}
                </select>
                <ChevronDown className="pointer-events-none absolute top-1/2 right-2 size-4 -translate-y-1/2 text-slate-500" aria-hidden />
            </span>
            {isManual && <span className="text-xs text-slate-500 dark:text-slate-400">choisi à la main</span>}
            {error && <span className="text-xs text-red-600 dark:text-red-400">{error}</span>}
        </div>
    );
}
