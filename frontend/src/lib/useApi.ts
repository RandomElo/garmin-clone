import { useEffect, useState } from "react";
import { useSync } from "./sync";

/** Loads data from the API and reloads it automatically after each completed sync. */
export function useApi<T>(load: () => Promise<T>, deps: unknown[] = []) {
    const { dataVersion } = useSync();
    const [data, setData] = useState<T | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        load()
            .then((result) => {
                if (cancelled) return;
                setData(result);
                setError(null);
            })
            .catch((err: unknown) => {
                if (!cancelled) setError(err instanceof Error ? err.message : "Erreur de chargement");
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [dataVersion, ...deps]);

    return { data, error, loading };
}
