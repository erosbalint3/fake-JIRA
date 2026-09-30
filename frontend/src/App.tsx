import { useLanguage } from './i18n';
import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { useAuth } from './auth';
import { useProjects } from './projects';
import { Layout } from './components/Layout';
import { Spinner } from './components/States';
import { AuthPage } from './pages/AuthPage';
import { BacklogPage } from './pages/BacklogPage';
import { BoardPage } from './pages/BoardPage';
import { MyWorkPage } from './pages/MyWorkPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { ForgotPasswordPage, ResetPasswordPage } from './pages/PasswordResetPages';
import { ProjectsPage } from './pages/ProjectsPage';
import { TaskDetailPage } from './pages/TaskDetailPage';

const AdminPage = lazy(() => import('./pages/AdminPage').then((m) => ({ default: m.AdminPage })));
const ReportsPage = lazy(() => import('./pages/ReportsPage').then((m) => ({ default: m.ReportsPage })));
const RoadmapPage = lazy(() => import('./pages/RoadmapPage').then((m) => ({ default: m.RoadmapPage })));
const ProjectSettingsPage = lazy(() => import('./pages/ProjectSettingsPage').then((m) => ({ default: m.ProjectSettingsPage })));
const ProfilePage = lazy(() => import('./pages/ProfilePage').then((m) => ({ default: m.ProfilePage })));
const SprintPage = lazy(() => import('./pages/SprintPage').then((m) => ({ default: m.SprintPage })));
const ReleasesPage = lazy(() => import('./pages/ReleasesPage').then((m) => ({ default: m.ReleasesPage })));
const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })));
const SearchPage = lazy(() => import('./pages/SearchPage').then((m) => ({ default: m.SearchPage })));
const CalendarPage = lazy(() => import('./pages/CalendarPage').then((m) => ({ default: m.CalendarPage })));
const ActivityPage = lazy(() => import('./pages/ActivityPage').then((m) => ({ default: m.ActivityPage })));
const TeamsPage = lazy(() => import('./pages/TeamsPage').then((m) => ({ default: m.TeamsPage })));
const AutomationPage = lazy(() => import('./pages/AutomationPage').then((m) => ({ default: m.AutomationPage })));
const SharedTaskPage = lazy(() => import('./pages/SharedTaskPage').then((m) => ({ default: m.SharedTaskPage })));
const TimelinePage = lazy(() => import('./pages/TimelinePage').then((m) => ({ default: m.TimelinePage })));
const PortfolioPage = lazy(() => import('./pages/PortfolioPage').then((m) => ({ default: m.PortfolioPage })));
const GoalsPage = lazy(() => import('./pages/GoalsPage').then((m) => ({ default: m.GoalsPage })));
const WikiPage = lazy(() => import('./pages/WikiPage').then((m) => ({ default: m.WikiPage })));
const MeetingsPage = lazy(() => import('./pages/MeetingsPage').then((m) => ({ default: m.MeetingsPage })));
const StandupPage = lazy(() => import('./pages/StandupPage').then((m) => ({ default: m.StandupPage })));
const TodayPage = lazy(() => import('./pages/TodayPage').then((m) => ({ default: m.TodayPage })));
const PortalPage = lazy(() => import('./pages/PortalPages').then((m) => ({ default: m.PortalPage })));
const TrackingPage = lazy(() => import('./pages/PortalPages').then((m) => ({ default: m.TrackingPage })));
const PublicRoadmapPage = lazy(() => import('./pages/PortalPages').then((m) => ({ default: m.PublicRoadmapPage })));
const PublicChangelogPage = lazy(() => import('./pages/PortalPages').then((m) => ({ default: m.PublicChangelogPage })));
const EmbedPage = lazy(() => import('./pages/PortalPages').then((m) => ({ default: m.EmbedPage })));
const KudosPage = lazy(() => import('./pages/KudosPage').then((m) => ({ default: m.KudosPage })));
const OAuthCompletePage = lazy(() => import('./pages/OAuthCompletePage').then((m) => ({ default: m.OAuthCompletePage })));

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
  // Switching language re-renders every page with the new strings.
  const lang = useLanguage();
  return (
    <Suspense key={lang} fallback={<div className="page"><Spinner /></div>}>
    <Routes>
      <Route path="/login" element={<GuestOnly><AuthPage mode="login" /></GuestOnly>} />
      <Route path="/register" element={<GuestOnly><AuthPage mode="register" /></GuestOnly>} />
      <Route path="/forgot-password" element={<GuestOnly><ForgotPasswordPage /></GuestOnly>} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/oauth-complete" element={<OAuthCompletePage />} />
      <Route path="/share/:token" element={<SharedTaskPage />} />
      <Route path="/portal/requests/:token" element={<TrackingPage />} />
      <Route path="/portal/:key" element={<PortalPage />} />
      <Route path="/public/:key/roadmap" element={<PublicRoadmapPage />} />
      <Route path="/public/:key/changelog" element={<PublicChangelogPage />} />
      <Route path="/embed/:key" element={<EmbedPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<Home />} />
        <Route path="/projects" element={<ProjectsPage />} />
        <Route path="/p/:key" element={<ProjectIndex />} />
        <Route path="/p/:key/board" element={<BoardPage />} />
        <Route path="/p/:key/backlog" element={<BacklogPage />} />
        <Route path="/p/:key/roadmap" element={<RoadmapPage />} />
        <Route path="/p/:key/timeline" element={<TimelinePage />} />
        <Route path="/p/:key/wiki" element={<WikiPage />} />
        <Route path="/p/:key/wiki/:slug" element={<WikiPage />} />
        <Route path="/p/:key/meetings" element={<MeetingsPage />} />
        <Route path="/p/:key/standup" element={<StandupPage />} />
        <Route path="/p/:key/reports" element={<ReportsPage />} />
        <Route path="/p/:key/releases" element={<ReleasesPage />} />
        <Route path="/p/:key/automation" element={<AutomationPage />} />
        <Route path="/p/:key/sprints/:id" element={<SprintPage />} />
        <Route path="/p/:key/settings" element={<ProjectSettingsPage />} />
        <Route path="/today" element={<TodayPage />} />
        <Route path="/my-work" element={<MyWorkPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/portfolio" element={<PortfolioPage />} />
        <Route path="/goals" element={<GoalsPage />} />
        <Route path="/kudos" element={<KudosPage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/calendar" element={<CalendarPage />} />
        <Route path="/activity" element={<ActivityPage />} />
        <Route path="/teams" element={<TeamsPage />} />
        <Route path="/tasks/:id" element={<TaskDetailPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="/backlog" element={<LegacyRedirect to="backlog" />} />
        <Route path="/board" element={<LegacyRedirect to="board" />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
    </Suspense>
  );
}
