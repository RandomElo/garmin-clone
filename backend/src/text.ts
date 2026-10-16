/**
 * French formatting for the generated texts (analysis, briefing).
 * Numbers follow the French style: "6,5", "3:12/km", "7 h 05".
 */

export const NBSP = " ";

export function dec(value: number, digits = 1): string {
    return value.toLocaleString("fr-FR", { maximumFractionDigits: digits, minimumFractionDigits: 0 });
}

/** "3:12/km" from a speed in m/s */
export function pace(speed: number): string {
    const s = Math.round(1000 / speed);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}/km`;
}

/** "7 h 05" from seconds */
export function hours(seconds: number): string {
    const m = Math.round(seconds / 60);
    return `${Math.floor(m / 60)}${NBSP}h${NBSP}${String(m % 60).padStart(2, "0")}`;
}

/** "45 min" or "1 h 10" for a gap expressed in seconds */
export function shortDuration(seconds: number): string {
    const m = Math.round(Math.abs(seconds) / 60);
    if (m < 60) return `${m}${NBSP}min`;
    return `${Math.floor(m / 60)}${NBSP}h${m % 60 ? `${NBSP}${String(m % 60).padStart(2, "0")}` : ""}`;
}

export const plural = (n: number, word: string, many = `${word}s`) => `${n} ${n > 1 ? many : word}`;

const weekdays = ["dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi"];

/** "lundi", "hier", "aujourd'hui" or "le 12 octobre" depending on how far the date is */
export function when(dateKey: string, today: string): string {
    const days = Math.round((Date.parse(`${today}T12:00:00`) - Date.parse(`${dateKey}T12:00:00`)) / 86_400_000);
    if (days === 0) return "aujourd'hui";
    if (days === 1) return "hier";
    const d = new Date(`${dateKey}T12:00:00`);
    if (days < 7) return weekdays[d.getDay()];
    return `le ${d.toLocaleDateString("fr-FR", { day: "numeric", month: "long" })}`;
}

export const ordinal = (n: number) => (n === 1 ? "1re" : `${n}e`);

/**
 * Picks one wording among several, always the same for a given seed (activity id, date),
 * so a text does not change on every reload but two sessions do not read the same.
 */
export function pick<T>(seed: number, variants: T[]): T {
    const x = Math.sin(seed * 9301 + 49297) * 233280;
    return variants[Math.floor((x - Math.floor(x)) * variants.length)];
}

export function seedOf(value: string | number): number {
    const s = String(value);
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
    return Math.abs(h);
}

export const capitalize = (s: string) => (s ? s[0].toUpperCase() + s.slice(1) : s);

/** "a, b et c" */
export function join(parts: string[]): string {
    if (parts.length <= 1) return parts.join("");
    return `${parts.slice(0, -1).join(", ")} et ${parts[parts.length - 1]}`;
}

export const mean = (values: number[]) => (values.length ? values.reduce((a, b) => a + b, 0) / values.length : 0);

export const sd = (values: number[]) => {
    if (values.length < 2) return 0;
    const m = mean(values);
    return Math.sqrt(values.reduce((t, v) => t + (v - m) ** 2, 0) / (values.length - 1));
};

export const median = (values: number[]) => {
    const sorted = [...values].sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};

export function addDays(dateKey: string, days: number): string {
    const d = new Date(`${dateKey}T12:00:00`);
    d.setDate(d.getDate() + days);
    const y = d.getFullYear();
    return `${y}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
