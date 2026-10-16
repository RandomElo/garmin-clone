/** 270° gauge with the value in the middle. `value` is a percentage (0-100). */
export function Gauge({ value, label, size = 180 }: { value: number; label: string; size?: number }) {
    const stroke = 14;
    const r = (size - stroke) / 2;
    const circumference = 2 * Math.PI * r;
    const arc = circumference * 0.75; // 270° visible, open at the bottom
    const filled = (arc * Math.min(Math.max(value, 0), 100)) / 100;

    return (
        <div className="relative" style={{ width: size, height: size }} role="meter" aria-valuenow={value} aria-valuemin={0} aria-valuemax={100} aria-label={label}>
            <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="rotate-[135deg]">
                <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={stroke} strokeLinecap="round" strokeDasharray={`${arc} ${circumference}`} className="stroke-slate-100 dark:stroke-slate-800" />
                <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={stroke} strokeLinecap="round" strokeDasharray={`${filled} ${circumference}`} className="stroke-accent transition-[stroke-dasharray] duration-700" />
            </svg>
            <div className="absolute inset-0 flex flex-col items-center justify-center">
                <span className="text-4xl font-semibold tabular-nums">{value} %</span>
                <span className="mt-1 text-xs text-slate-500 dark:text-slate-400">{label}</span>
            </div>
        </div>
    );
}

export function ProgressBar({ value, label, detail }: { value: number; label: string; detail: string }) {
    const pct = Math.round(Math.min(Math.max(value, 0), 1) * 100);
    return (
        <div>
            <div className="mb-1 flex justify-between gap-3 text-sm">
                <span>{label}</span>
                <span className="tabular-nums text-slate-500 dark:text-slate-400">{detail}</span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={label}>
                <div className="h-full rounded-full bg-accent transition-[width] duration-500" style={{ width: `${pct}%` }} />
            </div>
        </div>
    );
}
