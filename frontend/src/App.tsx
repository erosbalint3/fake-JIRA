import type { ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { useAuth } from './auth';
import { useProjects } from './projects';
import { Layout } from './components/Layout';
import { Spinner } from './components/States';
import { AdminPage } from './pages/AdminPage';
import { AuthPage } from './pages/AuthPage';
import { BacklogPage } from './pages/BacklogPage';
import { BoardPage } from './pages/BoardPage';
import { MyWorkPage } from './pages/MyWorkPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { ForgotPasswordPage, ResetPasswordPage } from './pages/PasswordResetPages';
import { ProfilePage } from './pages/ProfilePage';
import { ProjectSettingsPage } from './pages/ProjectSettingsPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { ReportsPage } from './pages/ReportsPage';
import { RoadmapPage } from './pages/RoadmapPage';
import { TaskDetailPage } from './pages/TaskDetailPage';

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="fullscreen"><Spinner /></div>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return children;
}

function GuestOnly({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <div className="fullscreen"><Spinner /></div>;
  return user ? <Navigate to="/" replace /> : children;
}

/** Sends people to their last project's board, or to Projects when they have none. */
function Home() {
  const { projects, lastKey } = useProjects();
  if (!projects) return <div className="page"><Spinner /></div>;
  const key = lastKey();
  return <Navigate to={key ? `/p/${key}/board` : '/projects'} replace />;
}

/** Old v2.0 URLs (/backlog, /board) now live inside a project. */
function LegacyRedirect({ to }: { to: 'backlog' | 'board' }) {
  const { projects, lastKey } = useProjects();
  if (!projects) return <div className="page"><Spinner /></div>;
  const key = lastKey();
  return <Navigate to={key ? `/p/${key}/${to}` : '/projects'} replace />;
}

function ProjectIndex() {
  const { key } = useParams();
  return <Navigate to={`/p/${key}/board`} replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<GuestOnly><AuthPage mode="login" /></GuestOnly>} />
      <Route path="/register" element={<GuestOnly><AuthPage mode="register" /></GuestOnly>} />
      <Route path="/forgot-password" element={<GuestOnly><ForgotPasswordPage /></GuestOnly>} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<Home />} />
        <Route path="/projects" element={<ProjectsPage />} />
        <Route path="/p/:key" element={<ProjectIndex />} />
        <Route path="/p/:key/board" element={<BoardPage />} />
        <Route path="/p/:key/backlog" element={<BacklogPage />} />
        <Route path="/p/:key/roadmap" element={<RoadmapPage />} />
        <Route path="/p/:key/reports" element={<ReportsPage />} />
        <Route path="/p/:key/settings" element={<ProjectSettingsPage />} />
        <Route path="/my-work" element={<MyWorkPage />} />
        <Route path="/tasks/:id" element={<TaskDetailPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="/backlog" element={<LegacyRedirect to="backlog" />} />
        <Route path="/board" element={<LegacyRedirect to="board" />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
