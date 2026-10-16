# Foulée

Une réécriture perso de Garmin Connect. Pour l'instant c'est une application web (pensée mobile d'abord, avec une barre d'onglets en bas sur petit écran) ; l'appli mobile viendra plus tard en réutilisant la même API.

Le backend se connecte à Garmin Connect, récupère une partie des données et les stocke en local dans SQLite. Le front n'interroge jamais Garmin directement, seulement l'API locale.

## Données récupérées

- Activités : tout l'historique Garmin (ou les N dernières avec `SYNC_ACTIVITIES_LIMIT`). Pour chacune : distance, temps, allure, FC, dénivelé, zones cardio, Training Effect, charge, dynamique de course, température.
- Détail d'activité : tours de la montre, courbes (allure, FC, altitude, cadence), tracé GPS. Les temps par kilomètre sont recalculés à partir des courbes.
- Santé, par jour, sur `SYNC_WELLNESS_DAYS` jours (365 par défaut) : pas, calories, minutes intensives, FC au repos, stress, Body Battery, respiration, sommeil par phases et score, VFC nocturne, poids.
- Forme : statut d'entraînement, VO2 max, charge aiguë et chronique, équilibre de charge, prédictions 5 km, 10 km, semi et marathon.

## Synchronisation

- Rien ne tourne tout seul par défaut. Sur la page Synchro, la carte « Automatisation » a une case « Synchro automatique » (créneaux et rattrapage), enregistrée en base : elle survit aux redémarrages et à la réinitialisation. Les boutons de synchro manuelle fonctionnent toujours.
- Automatique toutes les `SYNC_INTERVAL_MINUTES` (10 par défaut), seulement dans les créneaux `SYNC_WINDOWS` (par défaut 10:00-12:00 et 18:00-22:00, heure locale du serveur, réglée par `TZ`). En dehors, rien ne tourne.
- Bouton « Synchroniser aujourd'hui » (page Synchro, et icône en haut de chaque page) : seulement les données récentes (nouvelles activités et leurs détails, 3 derniers jours de santé, forme), en quelques secondes. « Synchro complète » ajoute un lot de l'historique manquant.
- Rattrapage par lots : chaque synchro complète récupère au plus `SYNC_DETAILS_PER_RUN` détails d'activités et `SYNC_WELLNESS_BACKFILL_PER_RUN` jours de santé anciens. Tant que l'historique n'est pas complet, les synchros s'enchaînent toutes les `SYNC_CATCHUP_INTERVAL_MINUTES` (2 par défaut), à toute heure.
- Une étape en échec n'empêche pas les autres : la synchro est alors marquée « partielle ».
- La page Synchro affiche la progression (avec le temps restant), une jauge de complétude de l'historique avec l'estimation du rattrapage, la prochaine synchro auto et l'historique.
- « Réinitialiser les données » vide la base locale et relance une synchro complète. Les jetons Garmin sont conservés.

## Fractionné

Les séances de fractionné sont détectées à partir des tours de la montre : étiquettes effort/récup de Garmin pour une séance programmée, écart d'allure pour des tours pris à la main. Les répétitions sont regroupées par type, et le nom devient par exemple « 3 × 1000 m (r : 2 min 30) + 4 × 200 m (r : 1 min) ». La fiche détaille chaque bloc : allure moyenne, temps, FC, récup, régularité et écart de chaque répétition.

## Analyse des séances de fractionné

Chaque séance de fractionné reçoit automatiquement une analyse écrite (fiche de la séance, et résumé sur l'accueil), calculée en local (`backend/src/session-analysis.ts`) :

- Performance : chaque répétition est comparée à tes répétitions du même format (tes « 1000 m », tes « 30 s »), à défaut de durée proche (±35 %), sur 12 mois. Les séances récentes comptent davantage (poids divisé par deux tous les 60 jours) pour suivre ta forme actuelle. La FC est comparée de la même façon, sauf sur les répétitions de moins de 90 s où elle n'a pas le temps de monter. Les blocs de plus de 15 min (échauffement pris pour une répétition) sont ignorés.
- Verdict : relatif à ta propre dispersion (écart-type robuste de toutes tes séances), de « Séance difficile » à « Excellente séance ». Plus lent mais avec une FC nettement plus basse donne « Séance contrôlée » (intensité choisie, pas un jour sans). Une grosse baisse de régime en fin de série fait perdre un cran.
- Déroulé : baisse de régime (dernier tiers contre premier tiers), régularité, récupération cardiaque (baisse de FC sur la première minute de chaque récup).
- Contexte : sommeil et score de sommeil de la nuit d'avant, VFC de la nuit, FC au repos, Body Battery au réveil, stress, fatigue d'entraînement, jours depuis la dernière séance dure, température. Chaque valeur est comparée à tes 30 derniers jours (60 pour la VFC).
- Corrélations personnelles : sur toutes tes séances, lien entre chaque facteur et l'allure (Spearman, avec un sens attendu par la physiologie pour écarter les hasards). Quand un lien est net, il sert à chiffrer l'effet en s/km ; sinon l'effet vient des études (manque de sommeil : Craven et al. 2022 ; VFC : entraînement guidé par la VFC, Plews, Vesterinen ; chaleur : environ 0,3 % par °C au-delà de l'optimum).

Le texte est construit à partir de ces éléments avec plusieurs formulations, toujours la même pour une séance donnée.

## Accueil

« Bonjour » (ou « Bonsoir ») avec ton prénom, récupéré sur le profil Garmin à chaque synchro, un court texte sur ta forme du jour et ta dernière séance, puis trois cartes :

- Km de course de la semaine (depuis lundi), avec le total de la semaine dernière.
- Charge des 7 jours : un clic explique l'écart avec les trois semaines d'avant en une phrase, puis en barres par type de séance (fractionné, sortie longue, séance soutenue, footing, autre sport), avec les séances concernées.
- Forme du jour, sur 100 : sommeil, VFC, FC au repos, Body Battery, stress et fatigue comparés à tes valeurs habituelles (`backend/src/readiness.ts`). Un clic détaille chaque facteur.

Le texte d'accueil décrit les facteurs de forme qui sortent le plus de l'ordinaire (pas toujours les mêmes) et ajoute le sujet le plus utile du jour : dernier fractionné, repos depuis la dernière séance dure, fatigue, volume de la semaine ou facteur qui pèse le plus dans ton historique.

En dessous, la course à pied des 26 dernières semaines (6 mois) façon Strava : distance, temps et dénivelé de la semaine choisie, et une courbe où chaque point sélectionne sa semaine.

La page Charge (lien dans le menu) montre la charge des 7 jours et des 4 semaines, la fatigue, et les courbes de la charge sur 4 semaines et de la fatigue sur 3 mois, avec un survol commun aux deux.

## Types de séance

Chaque activité a un type (`backend/src/categories.ts`), recalculé à chaque synchro. Un type choisi à la main sur la fiche de l'activité (menu « Type ») passe toujours en premier ; « Automatique » rend la main à la détection :

- Fractionné : séance détectée à partir des tours de la montre.
- Sortie longue : course de 19 km ou plus.
- Musculation : renforcement enregistré sur la montre.
- Footing : toute autre course. Autre sport : le reste.
- Compétition : jamais devinée en silence, voir « À classer ».

La page Activités filtre par type avec une liste déroulante, avec le nombre d'activités de chaque type.

### À classer

La page « À classer » (bouton sur la page Activités, `backend/src/review.ts`) liste les courses qui ressemblent à une compétition et n'ont pas encore été classées à la main. Ce ne sont que des règles, affichées sur chaque sortie :

| Règle | Points |
| --- | --- |
| Distance officielle à 3 % près (5, 10, 15 km, semi, marathon) | 1 |
| FC moyenne d'au moins 88 % de la FC max (86 % au-delà de 18 km, 80 % au-delà de 35 km) | 1 |
| Plus rapide que 95 % des sorties de distance proche (±20 %) des deux années d'avant | 1,5 |
| Nom typique d'une course (semi, trail, corrida, foulées…) | 1,5 |
| Training Effect aérobie de 4,2 ou plus, hors fractionné | 0,5 |
| Départ le samedi ou le dimanche entre 7 h et 11 h 30 | 0,5 |

Une sortie est listée à partir de 2,5 points. « C'est une compétition » ou « Non » la classe à la main : elle ne revient plus.

La photo de profil de l'accueil vient du profil Garmin.

FC de repos et FC max sont déduites des données (moyenne des 30 derniers jours, 99e centile des FC max sur deux ans) ; `HR_REST` et `HR_MAX` dans `.env` les remplacent.

## Bibliothèque Garmin

[`garmin-connect`](https://github.com/Pythe1337N/garmin-connect) (v1.6.2), la bibliothèque Node la plus utilisée. Garmin n'a pas d'API publique pour les particuliers : elle se connecte comme le site web (SSO puis jetons OAuth). Les jetons sont stockés dans `data/tokens/` (droits 600) pour éviter de se reconnecter à chaque synchro.

Le fork plus récent `@gooin/garmin-connect` n'a pas été retenu : il est publié avec du code obfusqué, ce qui n'est pas acceptable pour un module qui reçoit le mot de passe Garmin.

Limites connues : la double authentification (MFA) n'est pas gérée par la bibliothèque, et une modification du SSO côté Garmin peut casser la connexion. Tout l'accès à Garmin est isolé dans `backend/src/garmin.ts`, ce qui permet de changer de bibliothèque sans toucher au reste.

## Lancer le projet

Copier la configuration :

```bash
cp .env.example .env
```

puis renseigner `GARMIN_USERNAME` et `GARMIN_PASSWORD`, ou mettre `GARMIN_MOCK=true` pour essayer avec des données fictives.

### Avec Docker

```bash
docker compose up -d --build
```

L'appli est sur http://localhost:4242 (port fixé dans `docker-compose.yml`). La base SQLite et les jetons sont dans le volume `garmin-data`.

### En développement

```bash
cd backend && npm install && npm run dev     # API sur :3001
cd frontend && npm install && npm run dev    # front sur :5173, /api est proxifié
```

## Structure

```
backend/
  src/config.ts   variables d'environnement
  src/db.ts       modèles Sequelize (activities, activity_details, daily_metrics, fitness_snapshots, sync_runs)
  src/garmin.ts   accès Garmin (client réel + mode démo)
  src/sync.ts     déroulé d'une synchro, planification
  src/routes.ts   API REST
  src/session-analysis.ts  analyse écrite des séances de fractionné
  src/readiness.ts         forme du jour (sommeil, VFC, FC au repos…)
  src/briefing.ts          accueil et comparaison de charge
frontend/
  src/lib/        appels API, suivi de synchro partagé, formatage
  src/pages/      Accueil, Activités, Santé, Synchro
```

## API

| Méthode | Route                 | Rôle                                                   |
| ------- | --------------------- | ------------------------------------------------------ |
| GET | `/api/sync/status` | état courant, complétude, estimation du rattrapage, planning |
| POST | `/api/sync` | lance une synchro, `{"mode": "today"}` (défaut) ou `"full"` (409 si une est en cours) |
| GET | `/api/sync/history` | historique des synchros |
| DELETE | `/api/data` | vide la base locale et relance une synchro |
| PUT | `/api/preferences` | `{"autoSync": bool}`, case de l'automatisation |
| GET | `/api/activities` | liste paginée (`limit`, `offset`), filtrable (`category` : `interval`, `long`, `easy`, `other`), avec effort et charge de chaque séance et le nombre d'activités par filtre |
| GET | `/api/activities/:id` | activité avec tours, courbes, tracé GPS, détection et analyse écrite du fractionné |
| GET | `/api/daily?days=30` | métriques santé par jour (jusqu'à 365) |
| GET | `/api/summary` | distance, temps et dénivelé par semaine (`weeks`, `sport=running` pour la course seule) et dernière course |
| GET | `/api/fitness` | statut d'entraînement Garmin, charge, prédictions |
| GET | `/api/load?days=90` | charge 7 jours / 4 semaines, fatigue et effort par jour |
| GET | `/api/load/compare` | 7 derniers jours contre les 3 semaines d'avant, par type de séance |
| PUT | `/api/activities/:id/category` | type choisi à la main, `{"category": "competition"}` ou `null` pour revenir à la détection |
| GET | `/api/review` | courses qui ressemblent à une compétition, avec les règles remplies |
| GET | `/api/health/summary?days=30` | les cinq indicateurs de la page Santé et leur tendance |
| GET | `/api/briefing` | accueil : prénom, texte du jour, km, charge, forme du jour, dernier fractionné |
| GET | `/api/load/history` | forme mois par mois sur tout l'historique |
