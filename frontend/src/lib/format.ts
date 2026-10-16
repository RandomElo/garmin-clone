const dash = "–";

export function formatDistance(meters: number | null): string {
    if (meters === null) return dash;
    return `${(meters / 1000).toLocaleString("fr-FR", { maximumFractionDigits: 2, minimumFractionDigits: 2 })} km`;
}

export function formatDuration(seconds: number | null): string {
    if (seconds === null) return dash;
    const s = Math.round(seconds);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    const mm = String(m).padStart(2, "0");
    const ss = String(sec).padStart(2, "0");
    return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`;
}

export function formatHours(seconds: number | null): string {
    if (seconds === null) return dash;
    const h = Math.floor(seconds / 3600);
    const m = Math.round((seconds % 3600) / 60);
    return `${h} h ${String(m).padStart(2, "0")}`;
}

/** Pace in min/km from a speed in m/s. */
export function formatPace(speed: number | null): string {
    if (!speed) return dash;
    const secPerKm = 1000 / speed;
    return `${formatDuration(secPerKm)} /km`;
}

export function formatSpeed(speed: number | null): string {
    if (!speed) return dash;
    return `${(speed * 3.6).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} km/h`;
}

export function formatNumber(value: number | null, unit = ""): string {
    if (value === null) return dash;
    return `${Math.round(value).toLocaleString("fr-FR")}${unit ? ` ${unit}` : ""}`;
}

/** Garmin local times look like "2026-10-12 07:31:00". */
export function parseLocal(value: string): Date {
    return new Date(value.replace(" ", "T"));
}

export function formatDate(value: string | Date, opts: Intl.DateTimeFormatOptions = { dateStyle: "medium" }): string {
    const date = typeof value === "string" ? parseLocal(value) : value;
    return date.toLocaleString("fr-FR", opts);
}

export function formatRelative(value: string | Date | null): string {
    if (!value) return "jamais";
    const date = typeof value === "string" ? new Date(value) : value;
    const diff = Math.round((date.getTime() - Date.now()) / 1000);
    const rtf = new Intl.RelativeTimeFormat("fr", { numeric: "auto" });
    const abs = Math.abs(diff);
    if (abs < 45) return diff < 0 ? "à l'instant" : "dans quelques secondes";
    if (abs < 3600) return rtf.format(Math.round(diff / 60), "minute");
    if (abs < 86400) return rtf.format(Math.round(diff / 3600), "hour");
    return rtf.format(Math.round(diff / 86400), "day");
}

const activityTypes: Record<string, string> = {
    running: "Course",
    trail_running: "Trail",
    treadmill_running: "Tapis",
    track_running: "Piste",
    cycling: "Vélo",
    road_biking: "Vélo de route",
    walking: "Marche",
    hiking: "Randonnée",
    lap_swimming: "Natation",
    strength_training: "Musculation",
    crossfit: "CrossFit",
    high_intensity_interval_training: "HIIT",
    workout: "Entraînement",
    yoga: "Yoga",
    pilates: "Pilates",
};

export function activityTypeLabel(type: string): string {
    return activityTypes[type] ?? type.replaceAll("_", " ");
}

export const isRunning = (type: string) => type.includes("running");

const teLabels: Record<string, string> = {
    RECOVERY: "Récupération",
    AEROBIC_BASE: "Base aérobie",
    TEMPO: "Tempo",
    LACTATE_THRESHOLD: "Seuil lactique",
    THRESHOLD: "Seuil",
    VO2MAX: "VO2 max",
    ANAEROBIC_CAPACITY: "Capacité anaérobie",
    SPEED: "Vitesse",
};

export function teLabel(key: string | null): string | null {
    if (!key || key === "NONE" || key === "UNKNOWN") return null;
    return teLabels[key] ?? key.replaceAll("_", " ").toLowerCase();
}

const trainingStatuses: Record<string, string> = {
    PEAKING: "Pic de forme",
    PRODUCTIVE: "Productif",
    MAINTAINING: "Maintien",
    RECOVERY: "Récupération",
    UNPRODUCTIVE: "Improductif",
    DETRAINING: "Désentraînement",
    OVERREACHING: "Surcharge",
    STRAINED: "Sous tension",
    NO_STATUS: "Pas de statut",
};

export function trainingStatusLabel(key: string | null): string {
    if (!key) return "–";
    const prefix = Object.keys(trainingStatuses).find((k) => key.startsWith(k));
    return prefix ? trainingStatuses[prefix] : key.replaceAll("_", " ").toLowerCase();
}

const qualifiers: Record<string, string> = {
    OPTIMAL: "Optimale",
    LOW: "Basse",
    HIGH: "Élevée",
    VERY_HIGH: "Très élevée",
    BALANCED: "Équilibrée",
    UNBALANCED: "Déséquilibrée",
    POOR: "Faible",
};

export function qualifierLabel(key: string | null): string {
    if (!key) return "–";
    const prefix = Object.keys(qualifiers)
        .sort((a, b) => b.length - a.length)
        .find((k) => key.startsWith(k));
    return prefix ? qualifiers[prefix] : key.replaceAll("_", " ").toLowerCase();
}

/** "5,7" for scores on 10 or 20 */
export function formatScore(value: number | null): string {
    if (value === null) return "–";
    return value.toLocaleString("fr-FR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}
