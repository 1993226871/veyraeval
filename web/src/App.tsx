import { useState } from "react";
import { Navigate, NavLink, Outlet, Route, Routes } from "react-router-dom";
import {
  AuthProvider,
  useAuth,
  useWorkspace,
} from "./features/auth/AuthProvider";
import { LoginPage, PasswordPage } from "./features/auth/AuthPages";
import { CatalogPage } from "./features/catalog/CatalogPage";
import { SdkProjectsPage } from "./features/agents/SdkProjectsPage";
import { ExperimentsPage } from "./features/experiments/ExperimentsPage";
import { MyModelsPage } from "./features/models/MyModelsPage";
import { QuotaPage } from "./features/models/QuotaPage";
import { AdminPage } from "./features/admin/AdminPage";
import { AuditPage } from "./features/administration/AuditPage";
import { DeletionStatusPage } from "./features/administration/DeletionStatusPage";
import { ExperimentReport } from "./features/reports/ExperimentReport";
import { ComparisonView } from "./features/reports/ComparisonView";
import { ErrorNotice, Loading } from "./components/ui";

function Guard() {
  const { user } = useAuth();
  if (user === undefined) return <Loading />;
  if (!user) return <Navigate to="/login" replace />;
  if (user.must_change_password)
    return <Navigate to="/change-password" replace />;
  return <Layout />;
}
function AdminGuard() {
  const user = useWorkspace();
  return user.admin ? (
    <AdminPage />
  ) : (
    <Navigate to="/workspace/experiments" replace />
  );
}
function Layout() {
  const auth = useAuth();
  const user = useWorkspace();
  const [error, setError] = useState<unknown>();
  const links = [
    ["experiments", "实验"],
    ["rule-sets", "评分规则集"],
    ["agents", "SDK 接入"],
    ["quota", "个人额度"],
    ["models", "评审模型"],
    ["audit", "操作记录"],
  ] as const;
  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        跳到主内容
      </a>
      <aside className="sidebar">
        <NavLink className="wordmark" to="/workspace/experiments">
          VeyraEval<span>PLATFORM</span>
        </NavLink>
        <div className="workspace-label">个人工作空间</div>
        <nav aria-label="主导航">
          {links.map(([path, label]) => (
            <NavLink key={path} to={"/workspace/" + path}>
              {label}
            </NavLink>
          ))}
          {user.admin && <NavLink to="/admin">管理后台</NavLink>}
        </nav>
        <div className="sidebar-note">
          任务 · 证据 · 评测
          <br />
          让质量判断可以回溯
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <span>私有空间</span>
          <div className="account-menu">
            <span>{user.email}</span>
            <button
              onClick={async () => {
                setError(undefined);
                try {
                  await auth.logout();
                } catch (failure) {
                  setError(failure);
                }
              }}
            >
              退出登录
            </button>
          </div>
        </header>
        <main id="main">
          <ErrorNotice error={error} />
          <Outlet />
        </main>
      </div>
    </div>
  );
}
export function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/change-password" element={<PasswordPage />} />
        <Route element={<Guard />}>
          <Route path="/workspace/experiments" element={<ExperimentsPage />} />
          <Route path="/workspace/reports/:id" element={<ExperimentReport />} />
          <Route path="/workspace/comparisons" element={<ComparisonView />} />
          <Route
            path="/workspace/evaluators"
            element={<Navigate to="/workspace/rule-sets" replace />}
          />
          <Route
            path="/workspace/suites"
            element={<Navigate to="/workspace/rule-sets" replace />}
          />
          <Route
            path="/workspace/rule-sets"
            element={<CatalogPage key="rule-sets" kind="SUITE" />}
          />
          <Route path="/workspace/agents" element={<SdkProjectsPage />} />
          <Route path="/workspace/quota" element={<QuotaPage />} />
          <Route path="/workspace/models" element={<MyModelsPage />} />
          <Route path="/workspace/audit" element={<AuditPage />} />
          <Route
            path="/workspace/deletions/:id"
            element={<DeletionStatusPage />}
          />
          <Route path="/admin" element={<AdminGuard />} />
        </Route>
        <Route
          path="*"
          element={<Navigate to="/workspace/experiments" replace />}
        />
      </Routes>
    </AuthProvider>
  );
}
