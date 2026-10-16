// Interval analysis is computed by the API (backend/src/intervals.ts): only its shape lives here
import type { Lap } from "./api";

export type LapRole = "warmup" | "work" | "recovery" | "cooldown" | "other";

export interface LapInfo {
    lap: Lap;
    role: LapRole;
    group: number | null;
    rep: number | null;
}

export interface Recovery {
    duration: number;
    distance: number;
    speed: number;
    label: string;
}

export interface RepGroup {
    label: string;
    timeBased: boolean;
    reps: LapInfo[];
    avgSpeed: number;
    avgDuration: number;
    avgDistance: number;
    avgHr: number | null;
    bestSpeed: number;
    worstSpeed: number;
    recovery: Recovery | null;
}

export interface IntervalAnalysis {
    title: string;
    structured: boolean;
    groups: RepGroup[];
    laps: LapInfo[];
}
