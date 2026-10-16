import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { Bike, Check, ChevronDown, Dumbbell, Footprints, LayoutList, Route, Trophy, Zap, type LucideIcon } from "lucide-react";
import type { ActivityFilter } from "../lib/api";
import { formatNumber } from "../lib/format";

const filterOptions: { key: ActivityFilter; label: string; icon: LucideIcon }[] = [
    { key: "all", label: "Toutes les activités", icon: LayoutList },
    { key: "competition", label: "Compétition", icon: Trophy },
    { key: "interval", label: "Fractionné", icon: Zap },
    { key: "long", label: "Sortie longue", icon: Route },
    { key: "easy", label: "Footing", icon: Footprints },
    { key: "strength", label: "Musculation", icon: Dumbbell },
    { key: "other", label: "Autre sport", icon: Bike },
];

/**
 * Dropdown list with an icon per kind of session (a native select cannot show icons).
 * Listbox pattern: arrows move, Enter or Space picks, Escape closes, a click outside closes.
 */
export function FilterMenu({ value, counts, onChange }: { value: ActivityFilter; counts: Partial<Record<ActivityFilter, number>>; onChange: (f: ActivityFilter) => void }) {
    const [open, setOpen] = useState(false);
    const [active, setActive] = useState(0);
    const root = useRef<HTMLDivElement>(null);
    const list = useRef<HTMLUListElement>(null);
    const id = useId();
    const current = filterOptions.find((o) => o.key === value) ?? filterOptions[0];

    useEffect(() => {
        if (!open) return;
        list.current?.focus();
        const close = (e: MouseEvent) => !root.current?.contains(e.target as Node) && setOpen(false);
        document.addEventListener("mousedown", close);
        return () => document.removeEventListener("mousedown", close);
    }, [open]);

    const openMenu = () => {
        setActive(filterOptions.indexOf(current));
        setOpen(true);
    };

    const pick = (i: number) => {
        onChange(filterOptions[i].key);
        setOpen(false);
        root.current?.querySelector("button")?.focus();
    };

    const onListKey = (e: KeyboardEvent) => {
        const last = filterOptions.length - 1;
        if (e.key === "ArrowDown") setActive((i) => Math.min(i + 1, last));
        else if (e.key === "ArrowUp") setActive((i) => Math.max(i - 1, 0));
        else if (e.key === "Home") setActive(0);
        else if (e.key === "End") setActive(last);
        else if (e.key === "Enter" || e.key === " ") pick(active);
        else if (e.key === "Escape" || e.key === "Tab") {
            setOpen(false);
            if (e.key === "Escape") root.current?.querySelector("button")?.focus();
            return;
        } else return;
        e.preventDefault();
    };

    // Kinds with no activity are absent from the counts: 0 once the counts are known
    const count = (key: ActivityFilter) => (counts.all === undefined ? null : formatNumber(counts[key] ?? 0));
    const Icon = current.icon;

    return (
        <div ref={root} className="relative sm:w-64">
            <button
                type="button"
                aria-haspopup="listbox"
                aria-expanded={open}
                aria-label={`Type de séance : ${current.label}`}
                onClick={() => (open ? setOpen(false) : openMenu())}
                onKeyDown={(e) => {
                    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                        e.preventDefault();
                        openMenu();
                    }
                }}
                className={`flex w-full items-center gap-2.5 rounded-xl border bg-white py-2.5 pr-3 pl-3 text-left text-sm font-medium shadow-sm transition hover:border-accent/60 focus-visible:outline-2 focus-visible:outline-accent dark:bg-slate-900 ${open ? "border-accent" : "border-slate-200 dark:border-slate-800"}`}
            >
                <span className="grid size-7 shrink-0 place-items-center rounded-lg bg-accent/10 text-accent">
                    <Icon className="size-4" aria-hidden />
                </span>
                <span className="flex-1 truncate">{current.label}</span>
                {count(current.key) && <span className="text-xs text-slate-500 tabular-nums dark:text-slate-400">{count(current.key)}</span>}
                <ChevronDown className={`size-4 shrink-0 text-slate-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
            </button>

            {open && (
                <ul ref={list} role="listbox" tabIndex={-1} aria-label="Type de séance" aria-activedescendant={`${id}-${active}`} onKeyDown={onListKey} className="absolute right-0 z-30 mt-2 w-full min-w-72 overflow-hidden rounded-xl border border-slate-200 bg-white p-1 shadow-lg focus:outline-none dark:border-slate-800 dark:bg-slate-900">
                    {filterOptions.map((o, i) => {
                        const selected = o.key === value;
                        const OptionIcon = o.icon;
                        return (
                            <li key={o.key} id={`${id}-${i}`} role="option" aria-selected={selected} onMouseEnter={() => setActive(i)} onClick={() => pick(i)} className={`flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-2 text-sm ${i === active ? "bg-slate-100 dark:bg-slate-800" : ""} ${selected ? "font-semibold text-accent" : ""}`}>
                                <span className={`grid size-7 shrink-0 place-items-center rounded-lg ${selected ? "bg-accent text-white" : "bg-accent/10 text-accent"}`}>
                                    <OptionIcon className="size-4" aria-hidden />
                                </span>
                                <span className="flex-1 whitespace-nowrap">{o.label}</span>
                                {count(o.key) && <span className="text-xs font-normal text-slate-500 tabular-nums dark:text-slate-400">{count(o.key)}</span>}
                                <Check className={`size-4 shrink-0 ${selected ? "" : "invisible"}`} aria-hidden />
                            </li>
                        );
                    })}
                </ul>
            )}
        </div>
    );
}
