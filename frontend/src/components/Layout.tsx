import { Activity, HeartPulse, House, RefreshCw, Weight } from "lucide-react";
import { NavLink, Outlet } from "react-router-dom";
import { QuickSyncButton, SyncBadge } from "./SyncBadge";

const links = [
    { to: "/", label: "Accueil", icon: House },
    { to: "/activites", label: "Activités", icon: Activity },
    { to: "/sante", label: "Santé", icon: HeartPulse },
    { to: "/charge", label: "Charge", icon: Weight },
    { to: "/synchro", label: "Synchro", icon: RefreshCw },
];

export function Layout() {
    return (
        <div className="min-h-dvh">
            <aside className="fixed inset-y-0 left-0 z-20 hidden w-60 flex-col gap-1 overflow-y-auto border-r border-slate-200 bg-white p-4 md:flex dark:border-slate-800 dark:bg-slate-900">
                <div className="mb-6 flex items-center gap-2 px-2 text-lg font-semibold">
                    <img src="/favicon.svg" alt="" className="size-7" />
                    Foulée
                </div>
                {links.map(({ to, label, icon: Icon }) => (
                    <NavLink key={to} to={to} end={to === "/"} className={({ isActive }) => `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition ${isActive ? "bg-accent/10 text-accent" : "text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"}`}>
                        <Icon className="size-5" />
                        {label}
                    </NavLink>
                ))}
            </aside>

            {/* Fixed sidebar on desktop: the content is offset by its width */}
            <div className="flex min-h-dvh min-w-0 flex-col md:pl-60">
                <header className="sticky top-0 z-10 flex items-center justify-between gap-3 border-b border-slate-200 bg-white/80 px-4 py-3 backdrop-blur md:px-8 dark:border-slate-800 dark:bg-slate-950/80">
                    <div className="flex items-center gap-2 font-semibold md:invisible">
                        <img src="/favicon.svg" alt="" className="size-6" />
                        Foulée
                    </div>
                    <div className="flex min-w-0 items-center gap-2">
                        <SyncBadge />
                        <QuickSyncButton />
                    </div>
                </header>
                <main className="mx-auto w-full max-w-5xl flex-1 px-4 pt-6 pb-28 md:px-8 md:pb-10">
                    <Outlet />
                </main>
            </div>

            {/* Bottom tab bar on mobile, like the future native app */}
            <nav className="fixed inset-x-0 bottom-0 z-10 grid grid-cols-5 border-t border-slate-200 bg-white pb-[env(safe-area-inset-bottom)] md:hidden dark:border-slate-800 dark:bg-slate-900">
                {links.map(({ to, label, icon: Icon }) => (
                    <NavLink key={to} to={to} end={to === "/"} className={({ isActive }) => `flex flex-col items-center gap-1 py-2 text-xs font-medium ${isActive ? "text-accent" : "text-slate-500 dark:text-slate-400"}`}>
                        <Icon className="size-5" />
                        {label}
                    </NavLink>
                ))}
            </nav>
        </div>
    );
}
