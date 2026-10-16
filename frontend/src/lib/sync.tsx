import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { api, type SyncMode, type SyncStatus } from "./api";

interface SyncContextValue {
    status: SyncStatus | null;
    error: string | null;
    /** Increments each time a sync finishes, so pages know to reload their data. */
    dataVersion: number;
    starting: boolean;
    /** "today" (default): latest data in a few seconds; "full": also catches up the history */
    start: (mode?: SyncMode) => Promise<void>;
    refresh: () => Promise<void>;
    /** Deletes the local data and starts a fresh sync. */
    reset: () => Promise<void>;
}

const SyncContext = createContext<SyncContextValue | null>(null);

const POLL_RUNNING_MS = 1500;
const POLL_IDLE_MS = 30_000;

export function SyncProvider({ children }: { children: ReactNode }) {
    const [status, setStatus] = useState<SyncStatus | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [dataVersion, setDataVersion] = useState(0);
    const [starting, setStarting] = useState(false);
    const timer = useRef<number | undefined>(undefined);
    const wasRunning = useRef(false);

    const refresh = useCallback(async function poll(): Promise<void> {
        window.clearTimeout(timer.current);
        try {
            const next = await api.syncStatus();
            setStatus(next);
            setError(null);
            const running = next.current !== null;
            if (wasRunning.current && !running) setDataVersion((v) => v + 1);
            wasRunning.current = running;
            timer.current = window.setTimeout(poll, running ? POLL_RUNNING_MS : POLL_IDLE_MS);
        } catch {
            setError("Serveur injoignable");
            timer.current = window.setTimeout(poll, POLL_IDLE_MS / 3);
        }
    }, []);

    useEffect(() => {
        void refresh();
        return () => window.clearTimeout(timer.current);
    }, [refresh]);

    const start = useCallback(async (mode: SyncMode = "today") => {
        setStarting(true);
        try {
            await api.startSync(mode);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Impossible de lancer la synchronisation");
        } finally {
            setStarting(false);
            await refresh();
        }
    }, [refresh]);

    const reset = useCallback(async () => {
        setStarting(true);
        try {
            await api.resetData();
            setError(null);
            setDataVersion((v) => v + 1);
        } catch (err) {
            setError(err instanceof Error ? err.message : "Impossible de réinitialiser les données");
        } finally {
            setStarting(false);
            await refresh();
        }
    }, [refresh]);

    return <SyncContext.Provider value={{ status, error, dataVersion, starting, start, reset, refresh }}>{children}</SyncContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useSync(): SyncContextValue {
    const ctx = useContext(SyncContext);
    if (!ctx) throw new Error("useSync must be used inside <SyncProvider>");
    return ctx;
}
