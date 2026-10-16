import fs from "node:fs";
import { DataTypes, Model, Op, Sequelize, type CreationOptional, type InferAttributes, type InferCreationAttributes } from "sequelize";
import { config } from "./config.js";

fs.mkdirSync(config.dataDir, { recursive: true });

export const sequelize = new Sequelize({
    dialect: "sqlite",
    storage: config.dbPath,
    logging: false,
});

const FLOAT = DataTypes.FLOAT;
const INT = DataTypes.INTEGER;

export class Activity extends Model<InferAttributes<Activity>, InferCreationAttributes<Activity>> {
    declare id: number; // Garmin activityId
    declare name: string;
    declare type: string;
    declare startTimeLocal: string;
    declare locationName: string | null;
    declare distance: number | null; // meters
    declare duration: number | null; // seconds
    declare movingDuration: number | null;
    declare averageSpeed: number | null; // m/s
    declare maxSpeed: number | null;
    declare averageHr: number | null;
    declare maxHr: number | null;
    declare calories: number | null;
    declare elevationGain: number | null;
    declare elevationLoss: number | null;
    declare cadence: number | null;
    declare maxCadence: number | null;
    declare strideLength: number | null; // cm
    declare groundContactTime: number | null; // ms
    declare verticalOscillation: number | null; // cm
    declare verticalRatio: number | null; // %
    declare avgPower: number | null;
    declare normPower: number | null;
    declare minTemperature: number | null;
    declare maxTemperature: number | null;
    declare steps: number | null;
    declare vo2Max: number | null;
    declare aerobicTe: number | null;
    declare anaerobicTe: number | null;
    declare teLabel: string | null;
    declare trainingLoad: number | null;
    declare hrZones: number[] | null; // seconds in zones 1..5
    declare fastest1k: number | null; // seconds
    declare lapCount: number | null;
    declare hasPolyline: boolean;
    declare detailsSyncedAt: Date | null;
    /** Detected interval workout, e.g. "3 × 1000 m (r : 2 min 30)". Null for a steady session. */
    declare intervalTitle: string | null;
    /** Heart-rate TRIMP, used when Garmin gives no training load (older watches) */
    declare trimp: number | null;
    /** Kind of session chosen by hand in the app (race, long run…): always wins over the detection */
    declare manualCategory: string | null;
    /** Kind of session (interval, long, competition, strength, easy, other), see categories.ts */
    declare category: string | null;
}

Activity.init(
    {
        id: { type: DataTypes.BIGINT, primaryKey: true },
        name: { type: DataTypes.STRING, allowNull: false },
        type: { type: DataTypes.STRING, allowNull: false },
        startTimeLocal: { type: DataTypes.STRING, allowNull: false },
        locationName: DataTypes.STRING,
        distance: FLOAT,
        duration: FLOAT,
        movingDuration: FLOAT,
        averageSpeed: FLOAT,
        maxSpeed: FLOAT,
        averageHr: INT,
        maxHr: INT,
        calories: INT,
        elevationGain: FLOAT,
        elevationLoss: FLOAT,
        cadence: FLOAT,
        maxCadence: FLOAT,
        strideLength: FLOAT,
        groundContactTime: FLOAT,
        verticalOscillation: FLOAT,
        verticalRatio: FLOAT,
        avgPower: FLOAT,
        normPower: FLOAT,
        minTemperature: FLOAT,
        maxTemperature: FLOAT,
        steps: INT,
        vo2Max: FLOAT,
        aerobicTe: FLOAT,
        anaerobicTe: FLOAT,
        teLabel: DataTypes.STRING,
        trainingLoad: FLOAT,
        hrZones: DataTypes.JSON,
        fastest1k: FLOAT,
        lapCount: INT,
        hasPolyline: { type: DataTypes.BOOLEAN, allowNull: false, defaultValue: false },
        detailsSyncedAt: DataTypes.DATE,
        intervalTitle: DataTypes.STRING,
        trimp: FLOAT,
        manualCategory: DataTypes.STRING,
        category: DataTypes.STRING,
    },
    { sequelize, tableName: "activities", indexes: [{ fields: ["startTimeLocal"] }, { fields: ["detailsSyncedAt"] }, { fields: ["category"] }] },
);

export interface Lap {
    distance: number | null;
    duration: number | null;
    averageSpeed: number | null;
    averageHr: number | null;
    maxHr: number | null;
    elevationGain: number | null;
    elevationLoss: number | null;
    cadence: number | null;
    /** Set by structured workouts: ACTIVE, RECOVERY, WARMUP, COOLDOWN, REST, INTERVAL… */
    intensityType?: string | null;
    /** Workout step the lap belongs to: repetitions of the same step share it. */
    stepIndex?: number | null;
}

/** One chart sample: cumulative distance (m), elapsed time (s), and instant values. */
export interface Sample {
    d: number | null;
    t: number | null;
    hr: number | null;
    speed: number | null;
    ele: number | null;
    cad: number | null;
}

/** Heavy per-activity data, kept out of the activities table so lists stay light. */
export class ActivityDetail extends Model<InferAttributes<ActivityDetail>, InferCreationAttributes<ActivityDetail>> {
    declare activityId: number;
    declare laps: Lap[];
    declare track: [number, number][]; // [lat, lon]
    declare samples: Sample[];
}

ActivityDetail.init(
    {
        activityId: { type: DataTypes.BIGINT, primaryKey: true },
        laps: { type: DataTypes.JSON, allowNull: false },
        track: { type: DataTypes.JSON, allowNull: false },
        samples: { type: DataTypes.JSON, allowNull: false },
    },
    { sequelize, tableName: "activity_details" },
);

export class DailyMetric extends Model<InferAttributes<DailyMetric>, InferCreationAttributes<DailyMetric>> {
    declare date: string; // YYYY-MM-DD
    declare steps: number | null;
    declare stepGoal: number | null;
    declare distanceMeters: number | null;
    declare totalCalories: number | null;
    declare activeCalories: number | null;
    declare floors: number | null;
    declare moderateMinutes: number | null;
    declare vigorousMinutes: number | null;
    declare restingHr: number | null;
    declare minHr: number | null;
    declare maxHr: number | null;
    declare avgStress: number | null;
    declare maxStress: number | null;
    declare bodyBatteryHigh: number | null;
    declare bodyBatteryLow: number | null;
    declare bodyBatteryCharged: number | null;
    declare bodyBatteryDrained: number | null;
    declare respiration: number | null;
    declare sleepSeconds: number | null;
    declare deepSleepSeconds: number | null;
    declare lightSleepSeconds: number | null;
    declare remSleepSeconds: number | null;
    declare awakeSleepSeconds: number | null;
    declare sleepScore: number | null;
    declare hrvNight: number | null;
    declare hrvWeekly: number | null;
    declare hrvStatus: string | null;
    declare weightKg: number | null;
    /** Bumped when more fields are fetched per day, so older rows get re-synced. */
    declare version: number;
}

export const DAILY_VERSION = 2;

DailyMetric.init(
    {
        date: { type: DataTypes.STRING(10), primaryKey: true },
        steps: INT,
        stepGoal: INT,
        distanceMeters: FLOAT,
        totalCalories: INT,
        activeCalories: INT,
        floors: FLOAT,
        moderateMinutes: INT,
        vigorousMinutes: INT,
        restingHr: INT,
        minHr: INT,
        maxHr: INT,
        avgStress: INT,
        maxStress: INT,
        bodyBatteryHigh: INT,
        bodyBatteryLow: INT,
        bodyBatteryCharged: INT,
        bodyBatteryDrained: INT,
        respiration: FLOAT,
        sleepSeconds: INT,
        deepSleepSeconds: INT,
        lightSleepSeconds: INT,
        remSleepSeconds: INT,
        awakeSleepSeconds: INT,
        sleepScore: INT,
        hrvNight: INT,
        hrvWeekly: INT,
        hrvStatus: DataTypes.STRING,
        weightKg: FLOAT,
        version: { type: INT, allowNull: false, defaultValue: 1 },
    },
    { sequelize, tableName: "daily_metrics" },
);

/** Training state as Garmin computes it, one row per day it was synced. */
export class FitnessSnapshot extends Model<InferAttributes<FitnessSnapshot>, InferCreationAttributes<FitnessSnapshot>> {
    declare date: string;
    declare vo2Max: number | null;
    declare trainingStatus: string | null; // e.g. PRODUCTIVE, MAINTAINING
    declare acuteLoad: number | null;
    declare chronicLoad: number | null;
    declare chronicLoadMin: number | null;
    declare chronicLoadMax: number | null;
    declare acwr: number | null;
    declare acwrStatus: string | null;
    declare loadAerobicLow: number | null;
    declare loadAerobicHigh: number | null;
    declare loadAnaerobic: number | null;
    declare loadBalance: string | null;
    declare race5k: number | null; // seconds
    declare race10k: number | null;
    declare raceHalf: number | null;
    declare raceMarathon: number | null;
}

FitnessSnapshot.init(
    {
        date: { type: DataTypes.STRING(10), primaryKey: true },
        vo2Max: FLOAT,
        trainingStatus: DataTypes.STRING,
        acuteLoad: FLOAT,
        chronicLoad: FLOAT,
        chronicLoadMin: FLOAT,
        chronicLoadMax: FLOAT,
        acwr: FLOAT,
        acwrStatus: DataTypes.STRING,
        loadAerobicLow: FLOAT,
        loadAerobicHigh: FLOAT,
        loadAnaerobic: FLOAT,
        loadBalance: DataTypes.STRING,
        race5k: INT,
        race10k: INT,
        raceHalf: INT,
        raceMarathon: INT,
    },
    { sequelize, tableName: "fitness_snapshots" },
);

/** Small key/value store for sync bookkeeping (e.g. total activity count on Garmin). */
export class Setting extends Model<InferAttributes<Setting>, InferCreationAttributes<Setting>> {
    declare key: string;
    declare value: string;
}

Setting.init(
    {
        key: { type: DataTypes.STRING, primaryKey: true },
        value: { type: DataTypes.TEXT, allowNull: false },
    },
    { sequelize, tableName: "settings", timestamps: false },
);

export async function getSetting(key: string): Promise<string | null> {
    return (await Setting.findByPk(key))?.value ?? null;
}

export async function setSetting(key: string, value: string): Promise<void> {
    await Setting.upsert({ key, value });
}

export type SyncStatus = "running" | "success" | "partial" | "error";
export type StepStatus = "pending" | "running" | "success" | "error" | "skipped";
export type StepKey = "auth" | "activities" | "details" | "wellness" | "fitness";
export type SyncMode = "today" | "full";

export interface SyncStep {
    key: StepKey;
    label: string;
    status: StepStatus;
    count: number;
    total: number | null;
    error: string | null;
}

export class SyncRun extends Model<InferAttributes<SyncRun>, InferCreationAttributes<SyncRun>> {
    declare id: CreationOptional<number>;
    declare trigger: "manual" | "auto";
    /** today: only the latest data (quick); full: also catches up the history */
    declare mode: SyncMode;
    declare status: SyncStatus;
    declare startedAt: Date;
    declare finishedAt: Date | null;
    declare steps: SyncStep[];
    declare error: string | null;
}

SyncRun.init(
    {
        id: { type: DataTypes.INTEGER, primaryKey: true, autoIncrement: true },
        trigger: { type: DataTypes.STRING, allowNull: false },
        mode: { type: DataTypes.STRING, allowNull: false, defaultValue: "full" },
        status: { type: DataTypes.STRING, allowNull: false },
        startedAt: { type: DataTypes.DATE, allowNull: false },
        finishedAt: DataTypes.DATE,
        steps: { type: DataTypes.JSON, allowNull: false },
        error: DataTypes.TEXT,
    },
    { sequelize, tableName: "sync_runs", timestamps: false },
);

export async function initDb(): Promise<void> {
    // alter: adds the new columns to an existing database (everything here can be re-synced from Garmin anyway)
    await sequelize.sync({ alter: true });
    // A run still marked "running" means the server stopped mid-sync
    await SyncRun.update({ status: "error", error: "Interrompue (redémarrage du serveur)", finishedAt: new Date() }, { where: { status: "running" } });

    // v2 of the details stores the workout step of each lap (interval analysis): re-fetch multi-lap activities
    if ((await getSetting("detailsVersion")) !== "2") {
        await Activity.update({ detailsSyncedAt: null }, { where: { lapCount: { [Op.gt]: 1 } } });
        await setSetting("detailsVersion", "2");
    }
}
