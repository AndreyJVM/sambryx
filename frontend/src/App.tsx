import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LoginPage from "./pages/LoginPage";
import Layout from "./components/Layout";
import DashboardPage from "./pages/DashboardPage";
import SharesPage from "./pages/shares/SharesPage";
import UsersPage from "./pages/users/UsersPage";
import ConfigPage from "./pages/config/ConfigPage";
import ActiveDirectoryPage from "./pages/ad/ActiveDirectoryPage";
import GroupsPage from "./pages/groups/GroupsPage";
import FstecPage from "./pages/fstec/FstecPage";
import { ToastProvider } from "./components/ui/toast";
import { ConfirmProvider } from "./components/ui/confirm";

function App() {
  return (
    <ToastProvider>
      <ConfirmProvider>
        <BrowserRouter basename="/ui">
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            
            {/* Защищенные роуты с боковой панелью */}
            <Route element={<Layout />}>
              <Route path="/" element={<Navigate to="/shares" replace />} />
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/shares" element={<SharesPage />} />
              <Route path="/groups" element={<GroupsPage />} />
              <Route path="/users" element={<UsersPage />} />
              <Route path="/config" element={<ConfigPage />} />
              <Route path="/ad" element={<ActiveDirectoryPage />} />
              <Route path="/fstec" element={<FstecPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ConfirmProvider>
    </ToastProvider>
  );
}

export default App;

// CI frontend scope verification fixture.
