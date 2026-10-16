import type { ActivityCategory } from "./api";

/** Names of the kinds of session, as shown everywhere in the app */
export const categoryLabels: Record<ActivityCategory, string> = {
    competition: "Compétition",
    interval: "Fractionné",
    long: "Sortie longue",
    easy: "Footing",
    strength: "Musculation",
    other: "Autre sport",
};
