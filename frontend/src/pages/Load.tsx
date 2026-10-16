import { LoadCard } from "../components/LoadCard";
import { LoadCharts } from "../components/LoadCharts";
import { Card, EmptyState, ErrorBox, PageTitle } from "../components/ui";
import { api } from "../lib/api";
import { useApi } from "../lib/useApi";

export function Load() {
    // 3 months: long enough to see a training block build up, short enough to read session by session
    const { data, error, loading } = useApi(() => api.load(90));

    if (error) return <ErrorBox>{error}</ErrorBox>;

    const series = data?.series ?? [];

    return (
        <>
            <PageTitle subtitle="Charge et fatigue sur les 3 derniers mois">Charge d'entraînement</PageTitle>

            {data?.today && <LoadCard today={data.today} link={false} />}

            {!loading && !series.length ? (
                <EmptyState>Pas encore d'activité pour calculer la charge.</EmptyState>
            ) : (
                <div className="mt-6 grid gap-6">
                    <LoadCharts series={series} />
                    <Card title="Comment lire ces chiffres">
                        <ul className="space-y-2 text-sm text-slate-600 dark:text-slate-300">
                            <li>Effort (sur 10) : la dureté d'une séance. Environ 2 pour un footing court, 4 à 5 pour une sortie longue facile, 6 à 8 pour du fractionné ou du tempo, 9 et plus pour une course longue.</li>
                            <li>Charge 7 jours et 4 semaines (sur 20) : la somme des efforts de toutes tes séances sur la période, footings compris. 20 correspond à tes semaines (ou tes mois) les plus chargés depuis le début de l'historique.</li>
                            <li>Fatigue (sur 10) : ta semaine comparée à la semaine moyenne de ton mois. Sous 3, tu es frais ; de 3 à 7, la charge est équilibrée ; au-delà de 8,5, c'est la surcharge.</li>
                        </ul>
                    </Card>
                </div>
            )}
        </>
    );
}
