import "leaflet/dist/leaflet.css";
import { useState } from "react";
import { CircleMarker, MapContainer, Polyline, TileLayer } from "react-leaflet";

const LAYERS = {
    plan: {
        label: "Plan",
        url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    },
    satellite: {
        label: "Satellite",
        url: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        attribution: "Imagerie &copy; Esri, Maxar, Earthstar Geographics",
    },
};

type LayerKey = keyof typeof LAYERS;

export function RouteMap({ track }: { track: [number, number][] }) {
    const [layer, setLayer] = useState<LayerKey>("plan");
    const lats = track.map((p) => p[0]);
    const lons = track.map((p) => p[1]);
    const bounds: [[number, number], [number, number]] = [
        [Math.min(...lats), Math.min(...lons)],
        [Math.max(...lats), Math.max(...lons)],
    ];
    const accent = getComputedStyle(document.documentElement).getPropertyValue("--color-accent").trim() || "#2a78d6";
    const tiles = LAYERS[layer];

    return (
        <div className="relative">
            <MapContainer bounds={bounds} boundsOptions={{ padding: [24, 24] }} scrollWheelZoom={false} className="z-0 h-72 w-full rounded-xl md:h-96">
                {/* key forces Leaflet to swap the tile layer instead of patching it */}
                <TileLayer key={layer} attribution={tiles.attribution} url={tiles.url} maxZoom={19} />
                {/* White casing keeps the route readable on satellite imagery */}
                {layer === "satellite" && <Polyline positions={track} pathOptions={{ color: "#fff", weight: 7 }} />}
                <Polyline positions={track} pathOptions={{ color: accent, weight: 4 }} />
                <CircleMarker center={track[0]} radius={7} pathOptions={{ color: "#fff", weight: 2, fillColor: "#16a34a", fillOpacity: 1 }} />
                <CircleMarker center={track[track.length - 1]} radius={7} pathOptions={{ color: "#fff", weight: 2, fillColor: "#dc2626", fillOpacity: 1 }} />
            </MapContainer>
            <div className="absolute top-3 right-3 z-[1000] inline-flex rounded-lg bg-white p-1 text-xs shadow-md dark:bg-slate-900" role="group" aria-label="Fond de carte">
                {(Object.keys(LAYERS) as LayerKey[]).map((key) => (
                    <button key={key} onClick={() => setLayer(key)} aria-pressed={layer === key} className={`rounded-md px-2.5 py-1 font-medium transition ${layer === key ? "bg-accent text-white" : "text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"}`}>
                        {LAYERS[key].label}
                    </button>
                ))}
            </div>
        </div>
    );
}
