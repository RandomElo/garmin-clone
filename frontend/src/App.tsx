import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { EmptyState } from "./components/ui";
import { SyncProvider } from "./lib/sync";
import { Activities } from "./pages/Activities";
import { ActivityDetail } from "./pages/ActivityDetail";
import { Dashboard } from "./pages/Dashboard";
import { Health } from "./pages/Health";
import { Load } from "./pages/Load";
import { Review } from "./pages/Review";
import { Sync } from "./pages/Sync";

export default function App() {
    return (
        <SyncProvider>
            <BrowserRouter>
                <Routes>
                    <Route element={<Layout />}>
                        <Route index element={<Dashboard />} />
                        <Route path="activites" element={<Activities />} />
                        <Route path="activites/:id" element={<ActivityDetail />} />
                        <Route path="a-classer" element={<Review />} />
                        <Route path="sante" element={<Health />} />
                        <Route path="charge" element={<Load />} />
                        <Route path="synchro" element={<Sync />} />
                        <Route path="*" element={<EmptyState>Page introuvable.</EmptyState>} />
                    </Route>
                </Routes>
            </BrowserRouter>
        </SyncProvider>
    );
}
