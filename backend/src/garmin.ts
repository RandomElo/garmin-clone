import fs from "node:fs";
import path from "node:path";
import garminConnect from "garmin-connect";
import type { InferCreationAttributes } from "sequelize";
import { config } from "./config.js";
import { DAILY_VERSION, type Activity, type ActivityDetail, type DailyMetric, type FitnessSnapshot, type Lap, type Sample } from "./db.js";

// CommonJS package: named exports are not detected by Node's ESM loader
const { GarminConnect } = garminConnect;

// Computed locally, never sent by the activity list (so a list refresh does not wipe them)
export type ActivityInput = Omit<
    InferCreationAttributes<Activity>,
    "detailsSyncedAt" | "intervalTitle" | "trimp" | "manualCategory" | "category"
>;
export type DetailInput = InferCreationAttributes<ActivityDetail>;
export type DailyInput = InferCreationAttributes<DailyMetric>;
export type FitnessInput = InferCreationAttributes<FitnessSnapshot>;

/** What the sync needs from Garmin, so the real client and the mock are interchangeable. */
export interface GarminSource {
    authenticate(): Promise<void>;
    /** First name from the Garmin profile, known after authenticate() */
    firstName(): string | null;
    /** Profile picture URL from the Garmin profile, known after authenticate() */
    photoUrl(): string | null;
    countActivities(): Promise<number>;
    fetchActivities(start: number, limit: number): Promise<ActivityInput[]>;
    fetchActivityDetail(id: number): Promise<DetailInput>;
    fetchDay(date: Date): Promise<DailyInput>;
    fetchWeights(start: Date, end: Date): Promise<{ date: string; weightKg: number }[]>;
    fetchFitness(date: Date): Promise<FitnessInput>;
}

export function toDateKey(date: Date): string {
    const y = date.getFullYear();
    const m = String(date.getMonth() + 1).padStart(2, "0");
    const d = String(date.getDate()).padStart(2, "0");
    return `${y}-${m}-${d}`;
}

const num = (value: unknown): number | null => (typeof value === "number" && Number.isFinite(value) ? value : null);
const int = (value: unknown): number | null => {
    const n = num(value);
    return n === null ? null : Math.round(n);
};
const str = (value: unknown): string | null => (typeof value === "string" && value ? value : null);
// Garmin uses negative values (-1, -2) for "not enough data"
const positive = (value: unknown): number | null => {
    const n = int(value);
    return n === null || n < 0 ? null : n;
};

/** Each daily metric is optional: a missing one (no watch worn, no sleep tracked) must not fail the day. */
async function optional<T>(fn: () => Promise<T>): Promise<T | null> {
    try {
        return await fn();
    } catch {
        return null;
    }
}

// Raw Garmin payloads are loosely typed: only the fields we read matter
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Raw = Record<string, any>;

export function mapActivity(a: Raw): ActivityInput {
    const zones = [1, 2, 3, 4, 5].map((z) => num(a[`hrTimeInZone_${z}`]));
    return {
        id: a.activityId,
        name: a.activityName ?? "Activité",
        type: a.activityType?.typeKey ?? "other",
        startTimeLocal: a.startTimeLocal,
        locationName: str(a.locationName),
        distance: num(a.distance),
        duration: num(a.duration),
        movingDuration: num(a.movingDuration),
        averageSpeed: num(a.averageSpeed),
        maxSpeed: num(a.maxSpeed),
        averageHr: int(a.averageHR),
        maxHr: int(a.maxHR),
        calories: int(a.calories),
        elevationGain: num(a.elevationGain),
        elevationLoss: num(a.elevationLoss),
        cadence: num(a.averageRunningCadenceInStepsPerMinute),
        maxCadence: num(a.maxRunningCadenceInStepsPerMinute),
        strideLength: num(a.avgStrideLength),
        groundContactTime: num(a.avgGroundContactTime),
        verticalOscillation: num(a.avgVerticalOscillation),
        verticalRatio: num(a.avgVerticalRatio),
        avgPower: num(a.avgPower),
        normPower: num(a.normPower),
        minTemperature: num(a.minTemperature),
        maxTemperature: num(a.maxTemperature),
        steps: int(a.steps),
        vo2Max: num(a.vO2MaxValue),
        aerobicTe: num(a.aerobicTrainingEffect),
        anaerobicTe: num(a.anaerobicTrainingEffect),
        teLabel: str(a.trainingEffectLabel),
        trainingLoad: num(a.activityTrainingLoad),
        hrZones: zones.some((z) => z !== null) ? zones.map((z) => z ?? 0) : null,
        fastest1k: num(a.fastestSplit_1000),
        lapCount: int(a.lapCount),
        hasPolyline: Boolean(a.hasPolyline),
    };
}

function mapLaps(splits: Raw | null): Lap[] {
    return ((splits?.lapDTOs ?? []) as Raw[]).map((l) => ({
        distance: num(l.distance),
        duration: num(l.duration),
        averageSpeed: num(l.averageSpeed),
        averageHr: int(l.averageHR),
        maxHr: int(l.maxHR),
        elevationGain: num(l.elevationGain),
        elevationLoss: num(l.elevationLoss),
        cadence: num(l.averageRunCadence),
        intensityType: str(l.intensityType),
        stepIndex: int(l.wktStepIndex),
    }));
}

/** Garmin chart data is a table: metricDescriptors give the column index of each metric. */
function mapSamples(details: Raw | null): Sample[] {
    const descriptors = (details?.metricDescriptors ?? []) as Raw[];
    const col = (key: string) => descriptors.find((d) => d.key === key)?.metricsIndex as number | undefined;
    const idx = {
        d: col("sumDistance"),
        t: col("sumDuration"),
        hr: col("directHeartRate"),
        speed: col("directSpeed"),
        ele: col("directElevation"),
        // directRunCadence counts one foot; directDoubleCadence is steps per minute like the summary
        cad: col("directDoubleCadence") ?? col("directRunCadence"),
    };
    const pick = (row: unknown[], i: number | undefined) => (i === undefined ? null : num(row[i]));
    const round = (v: number | null, digits: number) => (v === null ? null : Number(v.toFixed(digits)));
    return ((details?.activityDetailMetrics ?? []) as Raw[]).map(({ metrics }) => ({
        d: round(pick(metrics, idx.d), 1),
        t: round(pick(metrics, idx.t), 1),
        hr: pick(metrics, idx.hr),
        speed: round(pick(metrics, idx.speed), 3),
        ele: round(pick(metrics, idx.ele), 1),
        cad: pick(metrics, idx.cad),
    }));
}

function mapTrack(details: Raw | null): [number, number][] {
    return ((details?.geoPolylineDTO?.polyline ?? []) as Raw[]).filter((p) => num(p.lat) !== null && num(p.lon) !== null).map((p) => [Number(p.lat.toFixed(6)), Number(p.lon.toFixed(6))]);
}

class RealGarminSource implements GarminSource {
    private client = new GarminConnect({ username: config.garmin.username, password: config.garmin.password }, config.garmin.domain);
    private displayName = "";
    private fullName = "";
    private photo = "";

    private get api(): string {
        return (this.client as unknown as { url: { GC_API: string } }).url.GC_API;
    }

    private get<T = Raw>(urlPath: string): Promise<T> {
        return this.client.get<T>(`${this.api}${urlPath}`);
    }

    async authenticate(): Promise<void> {
        const tokenFiles = ["oauth1_token.json", "oauth2_token.json"].map((f) => path.join(config.tokenDir, f));
        if (tokenFiles.every((f) => fs.existsSync(f))) {
            try {
                this.client.loadTokenByFile(config.tokenDir);
                ({ displayName: this.displayName, fullName: this.fullName, profileImageUrlMedium: this.photo } = await this.client.getUserProfile());
                return;
            } catch {
                // Stored tokens are invalid or expired beyond refresh: fall back to a full login
            }
        }
        await this.client.login();
        this.client.exportTokenToFile(config.tokenDir);
        for (const f of tokenFiles) fs.chmodSync(f, 0o600);
        ({ displayName: this.displayName, fullName: this.fullName, profileImageUrlMedium: this.photo } = await this.client.getUserProfile());
    }

    photoUrl(): string | null {
        return this.photo || null;
    }

    firstName(): string | null {
        return (this.fullName ?? "").trim().split(/\s+/)[0] || null;
    }

    async countActivities(): Promise<number> {
        const res = await this.get("/activitylist-service/activities/count");
        return int(res.totalCount) ?? 0;
    }

    async fetchActivities(start: number, limit: number): Promise<ActivityInput[]> {
        const activities = await this.get<Raw[]>(`/activitylist-service/activities/search/activities?start=${start}&limit=${limit}`);
        return activities.map(mapActivity);
    }

    async fetchActivityDetail(id: number): Promise<DetailInput> {
        const splits = await optional(() => this.get(`/activity-service/activity/${id}/splits`));
        const details = await optional(() => this.get(`/activity-service/activity/${id}/details?maxChartSize=600&maxPolylineSize=1500`));
        return { activityId: id, laps: mapLaps(splits), track: mapTrack(details), samples: mapSamples(details) };
    }

    async fetchDay(date: Date): Promise<DailyInput> {
        const day = toDateKey(date);
        const s = await optional(() => this.get(`/usersummary-service/usersummary/daily/${this.displayName}?calendarDate=${day}`));
        const sleep = await optional(() => this.get(`/sleep-service/sleep/dailySleepData?date=${day}`));
        const hrv = await optional(() => this.get(`/hrv-service/hrv/${day}`));
        const dto = sleep?.dailySleepDTO;
        return {
            date: day,
            steps: int(s?.totalSteps),
            stepGoal: int(s?.dailyStepGoal),
            distanceMeters: num(s?.totalDistanceMeters),
            totalCalories: int(s?.totalKilocalories),
            activeCalories: int(s?.activeKilocalories),
            floors: num(s?.floorsAscended),
            moderateMinutes: int(s?.moderateIntensityMinutes),
            vigorousMinutes: int(s?.vigorousIntensityMinutes),
            restingHr: int(s?.restingHeartRate),
            minHr: int(s?.minHeartRate),
            maxHr: int(s?.maxHeartRate),
            avgStress: positive(s?.averageStressLevel),
            maxStress: positive(s?.maxStressLevel),
            bodyBatteryHigh: int(s?.bodyBatteryHighestValue),
            bodyBatteryLow: int(s?.bodyBatteryLowestValue),
            bodyBatteryCharged: int(s?.bodyBatteryChargedValue),
            bodyBatteryDrained: int(s?.bodyBatteryDrainedValue),
            respiration: num(s?.avgWakingRespirationValue),
            sleepSeconds: int(dto?.sleepTimeSeconds),
            deepSleepSeconds: int(dto?.deepSleepSeconds),
            lightSleepSeconds: int(dto?.lightSleepSeconds),
            remSleepSeconds: int(dto?.remSleepSeconds),
            awakeSleepSeconds: int(dto?.awakeSleepSeconds),
            sleepScore: int(dto?.sleepScores?.overall?.value),
            hrvNight: int(hrv?.hrvSummary?.lastNightAvg ?? sleep?.avgOvernightHrv),
            hrvWeekly: int(hrv?.hrvSummary?.weeklyAvg),
            hrvStatus: str(hrv?.hrvSummary?.status),
            weightKg: null,
            version: DAILY_VERSION,
        };
    }

    async fetchWeights(start: Date, end: Date): Promise<{ date: string; weightKg: number }[]> {
        const res = await this.get(`/weight-service/weight/dateRange?startDate=${toDateKey(start)}&endDate=${toDateKey(end)}`);
        return ((res?.dateWeightList ?? []) as Raw[]).filter((w) => num(w.weight) !== null && str(w.calendarDate)).map((w) => ({ date: w.calendarDate, weightKg: Math.round(w.weight / 100) / 10 })); // grams -> kg, 1 decimal
    }

    async fetchFitness(date: Date): Promise<FitnessInput> {
        const day = toDateKey(date);
        const status = await optional(() => this.get(`/metrics-service/metrics/trainingstatus/aggregated/${day}`));
        const race = await optional(() => this.get(`/metrics-service/metrics/racepredictions/latest/${this.displayName}`));
        // Several devices can report: keep the primary training device
        const primary = (map: Raw | undefined) => {
            const values = Object.values(map ?? {}) as Raw[];
            return values.find((v) => v?.primaryTrainingDevice) ?? values[0];
        };
        const ts = primary(status?.mostRecentTrainingStatus?.latestTrainingStatusData);
        const acute = ts?.acuteTrainingLoadDTO;
        const balance = primary(status?.mostRecentTrainingLoadBalance?.metricsTrainingLoadBalanceDTOMap);
        const phrase = str(ts?.trainingStatusFeedbackPhrase);
        const vo2 = status?.mostRecentVO2Max?.generic;
        return {
            date: day,
            vo2Max: num(vo2?.vo2MaxPreciseValue ?? vo2?.vo2MaxValue),
            // "MAINTAINING_3" -> "MAINTAINING"
            trainingStatus: phrase ? phrase.replace(/_\d+$/, "") : null,
            acuteLoad: num(acute?.dailyTrainingLoadAcute),
            chronicLoad: num(acute?.dailyTrainingLoadChronic),
            chronicLoadMin: num(acute?.minTrainingLoadChronic),
            chronicLoadMax: num(acute?.maxTrainingLoadChronic),
            acwr: num(acute?.dailyAcuteChronicWorkloadRatio),
            acwrStatus: str(acute?.acwrStatus),
            loadAerobicLow: num(balance?.monthlyLoadAerobicLow),
            loadAerobicHigh: num(balance?.monthlyLoadAerobicHigh),
            loadAnaerobic: num(balance?.monthlyLoadAnaerobic),
            loadBalance: str(balance?.trainingBalanceFeedbackPhrase),
            race5k: int(race?.time5K),
            race10k: int(race?.time10K),
            raceHalf: int(race?.timeHalfMarathon),
            raceMarathon: int(race?.timeMarathon),
        };
    }
}

/** Deterministic fake data, seeded by index/date, so repeated syncs stay stable. */
class MockGarminSource implements GarminSource {
    private total = 300;

    private rand(seed: number): number {
        const x = Math.sin(seed) * 10000;
        return x - Math.floor(x);
    }

    private async latency(): Promise<void> {
        await new Promise((r) => setTimeout(r, 60));
    }

    async authenticate(): Promise<void> {
        await this.latency();
    }

    firstName(): string | null {
        return "Coureur";
    }

    photoUrl(): string | null {
        return null;
    }

    async countActivities(): Promise<number> {
        return this.total;
    }

    async fetchActivities(start: number, limit: number): Promise<ActivityInput[]> {
        await this.latency();
        const result: ActivityInput[] = [];
        const types = ["running", "running", "running", "trail_running", "cycling", "running"];
        const names = ["Footing", "Fractionné 10x400", "Sortie longue", "Seuil 3x10 min", "Récup", "Tempo 8 km"];
        for (let i = start; i < Math.min(start + limit, this.total); i++) {
            const r = this.rand(i + 1);
            const day = new Date();
            day.setDate(day.getDate() - Math.floor(i * 1.6));
            day.setHours(6 + Math.floor(r * 3), Math.floor(r * 60), 0, 0);
            const type = types[i % types.length];
            const cycling = type === "cycling";
            const distance = cycling ? 30000 + r * 40000 : 6000 + r * 15000;
            const speed = cycling ? 7.5 + r * 2 : 3.6 + r * 1.2;
            const duration = distance / speed;
            result.push(
                mapActivity({
                    activityId: 1_000_000 + this.total - i,
                    activityName: names[i % names.length],
                    activityType: { typeKey: type },
                    startTimeLocal: `${toDateKey(day)} ${day.toTimeString().slice(0, 8)}`,
                    locationName: "Paris",
                    distance,
                    duration,
                    movingDuration: duration * 0.98,
                    averageSpeed: speed,
                    maxSpeed: speed * 1.3,
                    averageHR: 140 + r * 30,
                    maxHR: 170 + r * 20,
                    calories: distance / 15,
                    elevationGain: r * (type === "trail_running" ? 800 : 120),
                    elevationLoss: r * (type === "trail_running" ? 800 : 120),
                    averageRunningCadenceInStepsPerMinute: cycling ? null : 170 + r * 14,
                    avgStrideLength: cycling ? null : 115 + r * 25,
                    vO2MaxValue: cycling ? null : 60,
                    aerobicTrainingEffect: 2 + r * 2.5,
                    anaerobicTrainingEffect: r * 2,
                    trainingEffectLabel: r > 0.6 ? "THRESHOLD" : "AEROBIC_BASE",
                    activityTrainingLoad: 50 + r * 200,
                    hrTimeInZone_1: duration * 0.1,
                    hrTimeInZone_2: duration * 0.35,
                    hrTimeInZone_3: duration * 0.3,
                    hrTimeInZone_4: duration * 0.2,
                    hrTimeInZone_5: duration * 0.05,
                    fastestSplit_1000: 1000 / (speed * 1.1),
                    lapCount: 1,
                    hasPolyline: true,
                }),
            );
        }
        return result;
    }

    async fetchActivityDetail(id: number): Promise<DetailInput> {
        await this.latency();
        const n = 300;
        const samples: Sample[] = [];
        const track: [number, number][] = [];
        for (let i = 0; i < n; i++) {
            const angle = (i / n) * Math.PI * 2;
            track.push([48.84 + Math.sin(angle) * 0.02, 2.44 + Math.cos(angle) * 0.03 + Math.sin(angle * 5 + id) * 0.002]);
            samples.push({
                d: i * 40,
                t: i * 10,
                hr: Math.round(135 + 25 * Math.sin(i / 20) + (i / n) * 10),
                speed: 3.8 + 0.6 * Math.sin(i / 15 + id),
                ele: 50 + 15 * Math.sin(i / 40),
                cad: 172 + 6 * Math.sin(i / 10),
            });
        }
        return { activityId: id, laps: [], track, samples };
    }

    async fetchDay(date: Date): Promise<DailyInput> {
        await this.latency();
        const day = toDateKey(date);
        const r = this.rand(Number(day.replaceAll("-", "")));
        const sleep = Math.round((6 + r * 2.5) * 3600);
        return {
            date: day,
            steps: Math.round(6000 + r * 14000),
            stepGoal: 10000,
            distanceMeters: 5000 + r * 15000,
            totalCalories: Math.round(2200 + r * 1000),
            activeCalories: Math.round(400 + r * 900),
            floors: Math.round(r * 25),
            moderateMinutes: Math.round(r * 30),
            vigorousMinutes: Math.round(r * 90),
            restingHr: Math.round(42 + r * 8),
            minHr: Math.round(40 + r * 6),
            maxHr: Math.round(150 + r * 30),
            avgStress: Math.round(15 + r * 25),
            maxStress: Math.round(60 + r * 35),
            bodyBatteryHigh: Math.round(70 + r * 30),
            bodyBatteryLow: Math.round(10 + r * 30),
            bodyBatteryCharged: Math.round(40 + r * 50),
            bodyBatteryDrained: Math.round(40 + r * 50),
            respiration: 13 + r * 3,
            sleepSeconds: sleep,
            deepSleepSeconds: Math.round(sleep * 0.2),
            lightSleepSeconds: Math.round(sleep * 0.55),
            remSleepSeconds: Math.round(sleep * 0.2),
            awakeSleepSeconds: Math.round(sleep * 0.05),
            sleepScore: Math.round(60 + r * 35),
            hrvNight: Math.round(55 + r * 30),
            hrvWeekly: 66,
            hrvStatus: "BALANCED",
            weightKg: null,
            version: DAILY_VERSION,
        };
    }

    async fetchWeights(): Promise<{ date: string; weightKg: number }[]> {
        return [];
    }

    async fetchFitness(date: Date): Promise<FitnessInput> {
        await this.latency();
        return {
            date: toDateKey(date),
            vo2Max: 59.9,
            trainingStatus: "PRODUCTIVE",
            acuteLoad: 898,
            chronicLoad: 739,
            chronicLoadMin: 591,
            chronicLoadMax: 1108,
            acwr: 1.2,
            acwrStatus: "OPTIMAL",
            loadAerobicLow: 1230,
            loadAerobicHigh: 1284,
            loadAnaerobic: 297,
            loadBalance: "BALANCED",
            race5k: 903,
            race10k: 1944,
            raceHalf: 4364,
            raceMarathon: 9732,
        };
    }
}

export function createGarminSource(): GarminSource {
    return config.garmin.mock ? new MockGarminSource() : new RealGarminSource();
}
