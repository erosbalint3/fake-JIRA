import type {
  Activity, AdminUser, Attachment, Backup, BoardColumn, BulkChange, Burndown, ChecklistItem, Comment,
  CreateTaskInput, DevLink, EmailFrequency, Epic, GithubSettings, ImportResult, Invite, LinkType, Notification,
  Priority, Profile, Project, RegistrationMode, Role, SavedFilter, Scope, Sprint, Status, Task, TaskInput,
  TaskLink, TimeEntry, TimeReport, User, VelocityEntry, TaskTemplate, RecurringTask, TaskType, Frequency,
  Release, RetroItem, RetroKind, SprintReview, PokerState, FlowDay, CycleReport, Throughput,
  SearchResult, SearchGroup, SearchField, TextHit, Team, Dashboard, Widget, RecentTask, CalendarEvent, FeedItem,
  AutomationRule, RuleAction, RuleRun, RuleTrigger, OutgoingWebhook, WebhookDelivery, ApiTokenInfo, ShareLinkInfo,
  PublicTask, CustomFieldDef, CustomFieldType, CustomFieldValue, ProjectTemplate, StorageUsage, SystemInfo, OffsiteStatus,
  Poll, DecisionEntry, KudosEntry, KudosWall, WikiPage, WikiPageSummary, WikiRevision, MeetingNote, MeetingKind, Standup,
  Resolution, Workflow, ProjectComponent, Approval, SprintGoal, SprintCapacity, Timeline, PortfolioRow, Goal, KeyResultInput,
  NotificationLevel, NotificationRule, NotificationSettings, Reminder, RunningTimer, TodayList, DaySummary, PersonalNotes,
  ForecastResult, Burnup, AgingWip, BugTrends, SlaTarget, TaskSla, SlaReport, TrendWeek, ReportSubscription, ReportKind,
  HealthCheckSummary, HealthCheckDetail,
} from './types';

const TOKEN_KEY = 'fakejira.token';

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public fieldErrors: Record<string, string> = {},
  ) {
    super(message);
  }
}

export const tokenStore = {
  get: (): string | null => {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set: (token: string | null) => {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token);
      else localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage unavailable: session lasts for this tab only */
    }
  },
};

/** True while API reads are answered from the offline copy kept by the service worker. */
let offline = false;
export const OFFLINE_CHANGED = 'fakejira:offline';
export function isOffline() {
  return offline;
}
function setOffline(next: boolean) {
  if (next === offline) return;
  offline = next;
  window.dispatchEvent(new Event(OFFLINE_CHANGED));
}

/** Forgets the offline copy of API reads (on logout, so the next person can't browse it). */
export function clearOfflineCache() {
  try {
    navigator.serviceWorker?.controller?.postMessage('clear-api-cache');
  } catch {
    /* no service worker */
  }
}

/** Called when the server rejects our token so the app can return to the login screen. */
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

function authHeaders(): Record<string, string> {
  const token = tokenStore.get();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function send(method: string, path: string, init: RequestInit = {}): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...init,
      method,
      headers: { Accept: 'application/json', ...authHeaders(), ...(init.headers as Record<string, string>) },
    });
  } catch {
    if (typeof navigator !== 'undefined' && !navigator.onLine) {
      setOffline(true);
      throw new ApiError(0, method === 'GET' ? "You're offline and this page hasn't been saved for offline use yet."
        : "You're offline. Changes can't be saved until you reconnect.");
    }
    throw new ApiError(0, 'Cannot reach the server. Is the backend running?');
  }
  setOffline(response.headers.get('X-FakeJIRA-Offline') === '1');
  if (response.status === 401 && tokenStore.get()) {
    onUnauthorized();
  }
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    let fieldErrors: Record<string, string> = {};
    try {
      const data = await response.json();
      if (data?.message) message = data.message;
      if (data?.fieldErrors) fieldErrors = data.fieldErrors;
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(response.status, message, fieldErrors);
  }
  return response;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await send(method, path, body === undefined ? {} : {
    body: JSON.stringify(body),
    headers: { 'Content-Type': 'application/json' },
  });
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export interface AuthResponse {
  token: string | null;
  /** Null while the two-factor step is pending. */
  user: User | null;
  pending: boolean;
  admin: boolean;
  /** Set when an authenticator code is needed: send it with the code to loginSecondStep. */
  challenge: string | null;
}

export interface TaskFilters {
  project?: string;
  scope?: Scope;
  q?: string;
  priority?: Priority | '';
  status?: Status | '';
  label?: string;
  sprint?: string;
  assignee?: string;
  epic?: string;
  type?: TaskType | '';
}

function query(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export const api = {
  login: (login: string, password: string) =>
    request<AuthResponse>('POST', '/auth/login', { login, password }),
  register: (username: string, email: string, password: string, inviteCode?: string) =>
    request<AuthResponse>('POST', '/auth/register', { username, email, password, inviteCode }),
  me: () => request<{ user: User; admin: boolean; mustChangePassword: boolean; language?: string }>('GET', '/auth/me'),
  loginSecondStep: (challenge: string, code: string) => request<AuthResponse>('POST', '/auth/login/2fa', { challenge, code }),
  logout: () => request<void>('POST', '/auth/logout'),
  passwordPolicy: () => request<PasswordRules>('GET', '/auth/password-policy'),
  providers: () => request<{ id: string; label: string }[]>('GET', '/auth/providers'),
  oauthUrl: (provider: string, invite?: string) =>
    request<{ url: string }>('POST', `/auth/oauth/${provider}/url`, { invite: invite || null }),
  unlinkIdentity: (provider: string) => request<void>('DELETE', `/profile/identities/${provider}`),
  sessions: () => request<SessionInfo[]>('GET', '/profile/sessions'),
  revokeSession: (id: string) => request<void>('DELETE', `/profile/sessions/${encodeURIComponent(id)}`),
  signOutOthers: () => request<{ signedOut: number }>('POST', '/profile/sessions/sign-out-others'),
  twoFactorSetup: () => request<{ secret: string; otpauthUrl: string }>('POST', '/profile/2fa/setup'),
  twoFactorEnable: (code: string) => request<{ recoveryCodes: string[] }>('POST', '/profile/2fa/enable', { code }),
  twoFactorDisable: (password: string, code: string) => request<void>('POST', '/profile/2fa/disable', { password, code }),
  recoveryCodes: (code: string) => request<{ recoveryCodes: string[] }>('POST', '/profile/2fa/recovery-codes', { code }),
  exportData: async () => (await send('GET', '/profile/export')).blob(),
  deleteAccount: (password: string, code: string) => request<void>('DELETE', '/profile', { password, code }),
  inviteInfo: (code: string) => request<{ valid: boolean; email: string | null; projectName: string | null;
    registrationMode: RegistrationMode }>('GET', `/auth/invite${query({ code })}`),
  forgotPassword: (email: string) => request<void>('POST', '/auth/forgot-password', { email }),
  resetPassword: (token: string, newPassword: string) =>
    request<void>('POST', '/auth/reset-password', { token, newPassword }),

  projects: () => request<Project[]>('GET', '/projects'),
  project: (key: string) => request<Project>('GET', `/projects/${key}`),
  createProject: (key: string, name: string, description: string, template?: string) =>
    request<Project>('POST', '/projects', { key, name, description, template: template || undefined }),
  updateProject: (key: string, name: string, description: string, extra: { kanban?: boolean; color?: string; autoSchedule?: boolean } = {}) =>
    request<Project>('PUT', `/projects/${key}`, { name, description, ...extra }),
  deleteProject: (key: string) => request<void>('DELETE', `/projects/${key}`),
  addMember: (key: string, login: string, role: Role = 'MEMBER') =>
    request<Project>('POST', `/projects/${key}/members`, { login, role }),
  transferOwnership: (key: string, userId: number) => request<Project>('PUT', `/projects/${key}/owner`, { userId }),
  chatHooks: (key: string) => request<ChatHook[]>('GET', `/projects/${key}/chat-hooks`),
  addChatHook: (key: string, kind: ChatHook['kind'], url: string, events: ChatEvent[]) =>
    request<ChatHook>('POST', `/projects/${key}/chat-hooks`, { kind, url, events }),
  updateChatHook: (id: number, events: ChatEvent[]) => request<ChatHook>('PUT', `/chat-hooks/${id}`, { events }),
  deleteChatHook: (id: number) => request<void>('DELETE', `/chat-hooks/${id}`),
  testChatHook: (id: number) => request<{ delivered: boolean; error: string | null }>('POST', `/chat-hooks/${id}/test`),
  setRole: (key: string, userId: number, role: Role) =>
    request<Project>('PUT', `/projects/${key}/members/${userId}/role`, { role }),
  removeMember: (key: string, userId: number) => request<void>('DELETE', `/projects/${key}/members/${userId}`),
  labels: (key: string) => request<string[]>('GET', `/projects/${key}/labels`),
  searchUsers: (q: string) => request<{ id: number; username: string }[]>('GET', `/users${query({ q })}`),

  sprints: (key: string) => request<Sprint[]>('GET', `/projects/${key}/sprints`),
  createSprint: (key: string, input: { name?: string; goal?: string; startDate?: string | null; endDate?: string | null }) =>
    request<Sprint>('POST', `/projects/${key}/sprints`, input),
  updateSprint: (id: number, input: { name: string; goal: string; startDate: string | null; endDate: string | null }) =>
    request<Sprint>('PUT', `/sprints/${id}`, input),
  startSprint: (id: number, input: { startDate?: string | null; endDate?: string | null } = {}) =>
    request<Sprint>('POST', `/sprints/${id}/start`, input),
  completeSprint: (id: number) => request<Sprint>('POST', `/sprints/${id}/complete`),
  deleteSprint: (id: number) => request<void>('DELETE', `/sprints/${id}`),
  burndown: (id: number) => request<Burndown>('GET', `/sprints/${id}/burndown`),
  velocity: (key: string) => request<VelocityEntry[]>('GET', `/projects/${key}/velocity`),
  timeReport: (key: string, from?: string, to?: string) =>
    request<TimeReport>('GET', `/projects/${key}/time${query({ from, to })}`),

  epics: (key: string) => request<Epic[]>('GET', `/projects/${key}/epics`),
  createEpic: (key: string, input: EpicInput) => request<Epic>('POST', `/projects/${key}/epics`, input),
  updateEpic: (id: number, input: EpicInput) => request<Epic>('PUT', `/epics/${id}`, input),
  deleteEpic: (id: number) => request<void>('DELETE', `/epics/${id}`),
  setEpicDependencies: (id: number, dependsOn: number[]) => request<Epic>('PUT', `/epics/${id}/dependencies`, { dependsOn }),

  releases: (key: string) => request<Release[]>('GET', `/projects/${key}/releases`),
  createRelease: (key: string, input: ReleaseInput) => request<Release>('POST', `/projects/${key}/releases`, input),
  updateRelease: (id: number, input: ReleaseInput) => request<Release>('PUT', `/releases/${id}`, input),
  deleteRelease: (id: number) => request<void>('DELETE', `/releases/${id}`),
  shipRelease: (id: number, moveUnfinishedTo: number | null) =>
    request<Release>('POST', `/releases/${id}/release`, { moveUnfinishedTo }),
  unshipRelease: (id: number) => request<Release>('POST', `/releases/${id}/unrelease`),
  releaseTasks: (id: number) => request<Task[]>('GET', `/releases/${id}/tasks`),
  releaseNotes: (id: number) => request<{ release: Release; markdown: string }>('GET', `/releases/${id}/notes`),
  setTaskRelease: (taskId: number, releaseId: number | null) =>
    request<Task>('PUT', `/tasks/${taskId}/release`, { releaseId }),

  retro: (sprintId: number) => request<RetroItem[]>('GET', `/sprints/${sprintId}/retro`),
  addRetroItem: (sprintId: number, kind: RetroKind, text: string) =>
    request<RetroItem>('POST', `/sprints/${sprintId}/retro`, { kind, text }),
  editRetroItem: (id: number, kind: RetroKind, text: string) => request<RetroItem>('PUT', `/retro/${id}`, { kind, text }),
  deleteRetroItem: (id: number) => request<void>('DELETE', `/retro/${id}`),
  voteRetroItem: (id: number) => request<RetroItem>('POST', `/retro/${id}/vote`),
  retroToTask: (id: number) => request<RetroItem>('POST', `/retro/${id}/task`),
  sprintReview: (sprintId: number) => request<SprintReview>('GET', `/sprints/${sprintId}/review`),

  poker: (taskId: number) => request<PokerState>('GET', `/tasks/${taskId}/poker`),
  startPoker: (taskId: number) => request<PokerState>('POST', `/tasks/${taskId}/poker`),
  pokerVote: (taskId: number, value: string | null) => request<PokerState>('PUT', `/tasks/${taskId}/poker/vote`, { value }),
  revealPoker: (taskId: number) => request<PokerState>('POST', `/tasks/${taskId}/poker/reveal`),
  acceptPoker: (taskId: number, points: number) => request<PokerState>('POST', `/tasks/${taskId}/poker/accept`, { points }),
  cancelPoker: (taskId: number) => request<void>('DELETE', `/tasks/${taskId}/poker`),

  search: (q: string, limit = 200) => request<SearchResult>('GET', `/search${query({ q, limit: String(limit) })}`),
  searchStats: (q: string, groupBy: string) => request<SearchGroup[]>('GET', `/search/stats${query({ q, groupBy })}`),
  searchFields: () => request<SearchField[]>('GET', '/search/fields'),
  textSearch: (q: string, limit = 30) => request<TextHit[]>('GET', `/search/text${query({ q, limit: String(limit) })}`),

  teams: () => request<Team[]>('GET', '/teams'),
  createTeam: (name: string, handle: string, description: string) =>
    request<Team>('POST', '/teams', { name, handle, description }),
  updateTeam: (id: number, name: string, handle: string, description: string) =>
    request<Team>('PUT', `/teams/${id}`, { name, handle, description }),
  deleteTeam: (id: number) => request<void>('DELETE', `/teams/${id}`),
  addTeamMember: (id: number, login: string) => request<Team>('POST', `/teams/${id}/members`, { login }),
  removeTeamMember: (id: number, userId: number) => request<Team>('DELETE', `/teams/${id}/members/${userId}`),

  dashboards: () => request<Dashboard[]>('GET', '/dashboards'),
  createDashboard: (name: string, widgets: Widget[]) => request<Dashboard>('POST', '/dashboards', { name, widgets }),
  updateDashboard: (id: number, name: string, widgets: Widget[]) =>
    request<Dashboard>('PUT', `/dashboards/${id}`, { name, widgets }),
  deleteDashboard: (id: number) => request<void>('DELETE', `/dashboards/${id}`),
  viewed: (taskId: number) => request<void>('POST', `/recent/${taskId}`),
  recent: () => request<RecentTask[]>('GET', '/recent'),

  calendar: (from: string, to: string, project?: string) =>
    request<CalendarEvent[]>('GET', `/calendar${query({ from, to, project })}`),
  calendarFeed: () => request<{ url: string | null }>('GET', '/profile/calendar-feed'),
  resetCalendarFeed: () => request<{ url: string | null }>('POST', '/profile/calendar-feed'),
  disableCalendarFeed: () => request<{ url: string | null }>('DELETE', '/profile/calendar-feed'),
  setAway: (from: string | null, until: string, message: string) =>
    request<User>('PUT', '/profile/away', { from, until, message }),
  clearAway: () => request<User>('DELETE', '/profile/away'),
  feed: (params: { project?: string; user?: string; before?: string; limit?: number } = {}) =>
    request<FeedItem[]>('GET', `/activity${query({ ...params })}`),
  projectEmail: (key: string) => request<{ address: string | null }>('GET', `/projects/${key}/email-address`),

  flow: (key: string, days = 30) => request<FlowDay[]>('GET', `/projects/${key}/flow?days=${days}`),
  cycleTime: (key: string, days = 90) => request<CycleReport>('GET', `/projects/${key}/cycle-time?days=${days}`),
  throughput: (key: string, weeks = 12) => request<Throughput[]>('GET', `/projects/${key}/throughput?weeks=${weeks}`),

  columns: (key: string) => request<BoardColumn[]>('GET', `/projects/${key}/columns`),
  addColumn: (key: string, input: ColumnInput) => request<BoardColumn[]>('POST', `/projects/${key}/columns`, input),
  updateColumn: (id: number, input: ColumnInput) => request<BoardColumn[]>('PUT', `/columns/${id}`, input),
  moveColumn: (id: number, direction: -1 | 1) => request<BoardColumn[]>('POST', `/columns/${id}/move${query({ direction })}`),
  deleteColumn: (id: number) => request<BoardColumn[]>('DELETE', `/columns/${id}`),

  filters: (key: string) => request<SavedFilter[]>('GET', `/projects/${key}/filters`),
  saveFilter: (key: string, name: string, filterQuery: string, shared: boolean) =>
    request<SavedFilter>('POST', `/projects/${key}/filters`, { name, query: filterQuery, shared }),
  deleteFilter: (id: number) => request<void>('DELETE', `/filters/${id}`),

  github: (key: string) => request<GithubSettings>('GET', `/projects/${key}/github`),
  enableGithub: (key: string) => request<GithubSettings>('POST', `/projects/${key}/github`),
  setGithubAutoDone: (key: string, autoDone: boolean) => request<GithubSettings>('PUT', `/projects/${key}/github`, { autoDone }),
  disableGithub: (key: string) => request<void>('DELETE', `/projects/${key}/github`),

  exportSearch: async (q: string, format: 'xlsx' | 'pdf') => (await send('GET', `/search/export${query({ q, format })}`)).blob(),
  exportReports: async (key: string, format: 'xlsx' | 'pdf') => (await send('GET', `/projects/${key}/reports/export?format=${format}`)).blob(),
  exportCsv: async (key: string) => (await send('GET', `/projects/${key}/export.csv`)).blob(),
  customFields: (key: string) => request<CustomFieldDef[]>('GET', `/projects/${key}/fields`),
  createCustomField: (key: string, name: string, type: CustomFieldType, options: string[]) =>
    request<CustomFieldDef>('POST', `/projects/${key}/fields`, { name, type, options }),
  updateCustomField: (id: number, name: string, type: CustomFieldType, options: string[]) =>
    request<CustomFieldDef>('PUT', `/fields/${id}`, { name, type, options }),
  deleteCustomField: (id: number) => request<void>('DELETE', `/fields/${id}`),
  taskFields: (taskId: number) => request<CustomFieldValue[]>('GET', `/tasks/${taskId}/fields`),
  setTaskField: (taskId: number, fieldId: number, value: string | null) =>
    request<CustomFieldValue>('PUT', `/tasks/${taskId}/fields/${fieldId}`, { value }),
  projectTemplates: () => request<ProjectTemplate[]>('GET', '/project-templates'),
  projectStorage: (key: string) => request<StorageUsage>('GET', `/projects/${key}/storage`),
  system: () => request<SystemInfo>('GET', '/admin/system'),
  setQuotas: (projectMb: number, totalMb: number) => request<{ projectMb: number; totalMb: number }>('PUT', '/admin/quotas', { projectMb, totalMb }),
  uploadOffsite: () => request<OffsiteStatus>('POST', '/admin/offsite/upload'),
  checkForUpdate: () => request<SystemInfo['update']>('POST', '/admin/update-check'),

  importJira: async (key: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return (await send('POST', `/projects/${key}/import/jira`, { body: form })).json() as Promise<ImportResult>;
  },
  importTrello: async (key: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return (await send('POST', `/projects/${key}/import/trello`, { body: form })).json() as Promise<ImportResult>;
  },

  automations: (key: string) => request<AutomationRule[]>('GET', `/projects/${key}/automations`),
  createAutomation: (key: string, input: RuleInput) => request<AutomationRule>('POST', `/projects/${key}/automations`, input),
  updateAutomation: (id: number, input: RuleInput) => request<AutomationRule>('PUT', `/automations/${id}`, input),
  deleteAutomation: (id: number) => request<void>('DELETE', `/automations/${id}`),
  runAutomation: (id: number) => request<{ tasks: number }>('POST', `/automations/${id}/run`),
  automationLog: (id: number) => request<RuleRun[]>('GET', `/automations/${id}/log`),

  webhooks: (key: string) => request<OutgoingWebhook[]>('GET', `/projects/${key}/webhooks`),
  createWebhook: (key: string, url: string, events: string[]) =>
    request<OutgoingWebhook>('POST', `/projects/${key}/webhooks`, { url, events }),
  updateWebhook: (id: number, url: string, events: string[], enabled: boolean) =>
    request<OutgoingWebhook>('PUT', `/webhooks/${id}`, { url, events, enabled }),
  rotateWebhookSecret: (id: number) => request<OutgoingWebhook>('POST', `/webhooks/${id}/secret`),
  deleteWebhook: (id: number) => request<void>('DELETE', `/webhooks/${id}`),
  testWebhook: (id: number) => request<WebhookDelivery>('POST', `/webhooks/${id}/test`),
  webhookDeliveries: (id: number) => request<WebhookDelivery[]>('GET', `/webhooks/${id}/deliveries`),

  apiTokens: () => request<ApiTokenInfo[]>('GET', '/profile/tokens'),
  createApiToken: (name: string, scope: 'READ' | 'WRITE', expiresInDays: number | null) =>
    request<ApiTokenInfo>('POST', '/profile/tokens', { name, scope, expiresInDays }),
  revokeApiToken: (id: number) => request<void>('DELETE', `/profile/tokens/${id}`),

  shares: (taskId: number) => request<ShareLinkInfo[]>('GET', `/tasks/${taskId}/shares`),
  createShare: (taskId: number, includeComments: boolean, expiresInDays: number | null) =>
    request<ShareLinkInfo>('POST', `/tasks/${taskId}/shares`, { includeComments, expiresInDays }),
  revokeShare: (id: number) => request<void>('DELETE', `/shares/${id}`),
  /** Public: no sign-in (and no token sent, so a stale session cannot get in the way). */
  publicTask: async (token: string) => {
    const response = await fetch(`/api/public/share/${encodeURIComponent(token)}`, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new ApiError(response.status, response.status === 404 ? 'This link does not exist or has expired.' : 'Could not load this task.');
    return response.json() as Promise<PublicTask>;
  },

  importCsv: async (key: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return (await send('POST', `/projects/${key}/import`, { body: form })).json() as Promise<ImportResult>;
  },

  tasks: (filters: TaskFilters = {}) => request<Task[]>('GET', `/tasks${query({ ...filters })}`),
  task: (id: number) => request<Task>('GET', `/tasks/${id}`),
  taskByKey: (key: string) => request<Task>('GET', `/tasks/key/${encodeURIComponent(key)}`),
  subtasks: (id: number) => request<Task[]>('GET', `/tasks/${id}/subtasks`),
  bulk: (taskIds: number[], change: BulkChange) => request<Task[]>('POST', '/tasks/bulk', { taskIds, ...change }),
  moveToColumn: (id: number, columnId: number, resolution?: Resolution | null) =>
    request<Task>('PATCH', `/tasks/${id}/column`, { columnId, resolution: resolution ?? null }),
  createTask: (input: CreateTaskInput) => request<Task>('POST', '/tasks', input),
  /** {@code expected}: the task's updatedAt when the form opened; the server refuses to overwrite newer changes. */
  updateTask: (id: number, input: TaskInput, expected?: string) =>
    request<Task>('PUT', `/tasks/${id}${expected ? `?expected=${encodeURIComponent(expected)}` : ''}`, input),
  setStatus: (id: number, status: Status, resolution?: Resolution | null) =>
    request<Task>('PATCH', `/tasks/${id}/status`, { status, resolution: resolution ?? null }),
  setResolution: (id: number, resolution: Resolution) => request<Task>('PUT', `/tasks/${id}/resolution`, { resolution }),
  schedule: (id: number, input: { startDate: string | null; dueDate: string | null; estimateMinutes: number | null }) =>
    request<Task>('PUT', `/tasks/${id}/schedule`, input),
  setHelpers: (id: number, userIds: number[]) => request<Task>('PUT', `/tasks/${id}/helpers`, { userIds }),
  archiveTask: (id: number) => request<Task>('POST', `/tasks/${id}/archive`),
  unarchiveTask: (id: number) => request<Task>('DELETE', `/tasks/${id}/archive`),
  archiveDone: (projectKey: string, days: number) =>
    request<{ archived: number }>('POST', `/tasks/archive-done?project=${encodeURIComponent(projectKey)}&days=${days}`),
  archivedTasks: (projectKey: string) =>
    request<Task[]>('GET', `/tasks?project=${encodeURIComponent(projectKey)}&archived=only`),
  setTaskComponents: (id: number, ids: number[]) => request<Task>('PUT', `/tasks/${id}/components`, { ids }),

  workflow: (key: string) => request<Workflow>('GET', `/projects/${key}/workflow`),
  saveWorkflow: (key: string, workflow: { restricted: boolean; columns: { id: number; required: string[] }[];
    transitions: { fromId: number | null; toId: number }[] }) => request<Workflow>('PUT', `/projects/${key}/workflow`, workflow),
  components: (key: string) => request<ProjectComponent[]>('GET', `/projects/${key}/components`),
  createComponent: (key: string, input: { name: string; description: string; leadId: number | null }) =>
    request<ProjectComponent>('POST', `/projects/${key}/components`, input),
  updateComponent: (id: number, input: { name: string; description: string; leadId: number | null }) =>
    request<ProjectComponent>('PUT', `/components/${id}`, input),
  deleteComponent: (id: number) => request<void>('DELETE', `/components/${id}`),
  typeChecklists: (key: string) => request<Record<TaskType, string[]>>('GET', `/projects/${key}/type-checklists`),
  saveTypeChecklist: (key: string, type: TaskType, items: string[]) =>
    request<string[]>('PUT', `/projects/${key}/type-checklists/${type}`, { items }),

  approvals: (id: number) => request<Approval[]>('GET', `/tasks/${id}/approvals`),
  requestApproval: (id: number, approverId: number, note: string) =>
    request<Approval>('POST', `/tasks/${id}/approvals`, { approverId, note }),
  decideApproval: (id: number, approve: boolean, note: string) =>
    request<Approval>('POST', `/approvals/${id}/decision`, { approve, note }),
  withdrawApproval: (id: number) => request<void>('DELETE', `/approvals/${id}`),
  myApprovals: () => request<Approval[]>('GET', '/approvals/mine'),

  sprintGoals: (sprintId: number) => request<SprintGoal[]>('GET', `/sprints/${sprintId}/goals`),
  addSprintGoal: (sprintId: number, text: string) => request<SprintGoal>('POST', `/sprints/${sprintId}/goals`, { text }),
  updateSprintGoal: (id: number, change: { text?: string; done?: boolean }) =>
    request<SprintGoal>('PATCH', `/sprint-goals/${id}`, change),
  deleteSprintGoal: (id: number) => request<void>('DELETE', `/sprint-goals/${id}`),
  sprintCapacity: (sprintId: number) => request<SprintCapacity>('GET', `/sprints/${sprintId}/capacity`),
  setCapacity: (sprintId: number, userId: number, hoursPerDay: number, daysOff: number) =>
    request<SprintCapacity>('PUT', `/sprints/${sprintId}/capacity/${userId}`, { hoursPerDay, daysOff }),

  timeline: (key: string) => request<Timeline>('GET', `/projects/${key}/timeline`),

  polls: (taskId: number) => request<Poll[]>('GET', `/tasks/${taskId}/polls`),
  createPoll: (taskId: number, question: string, options: string[], multiple: boolean) =>
    request<Poll>('POST', `/tasks/${taskId}/polls`, { question, options, multiple }),
  vote: (pollId: number, options: number[]) => request<Poll>('POST', `/polls/${pollId}/vote`, { options }),
  closePoll: (pollId: number, recordDecision: boolean) => request<Poll>('POST', `/polls/${pollId}/close`, { recordDecision }),
  deletePoll: (pollId: number) => request<void>('DELETE', `/polls/${pollId}`),
  decisions: (key: string) => request<DecisionEntry[]>('GET', `/projects/${key}/decisions`),
  taskDecisions: (taskId: number) => request<DecisionEntry[]>('GET', `/tasks/${taskId}/decisions`),
  recordDecision: (key: string, text: string, context: string, taskId?: number | null) =>
    request<DecisionEntry>('POST', `/projects/${key}/decisions`, { text, context, taskId: taskId ?? null }),
  deleteDecision: (id: number) => request<void>('DELETE', `/decisions/${id}`),
  giveKudos: (taskId: number, toUserId: number, message: string, emoji: string) =>
    request<KudosEntry>('POST', `/tasks/${taskId}/kudos`, { toUserId, message, emoji }),
  taskKudos: (taskId: number) => request<KudosEntry[]>('GET', `/tasks/${taskId}/kudos`),
  kudosWall: (project?: string) => request<KudosWall>('GET', `/kudos${project ? `?project=${project}` : ''}`),
  wikiPages: (key: string) => request<WikiPageSummary[]>('GET', `/projects/${key}/wiki`),
  wikiPage: (key: string, slug: string) => request<WikiPage>('GET', `/projects/${key}/wiki/${encodeURIComponent(slug)}`),
  createWikiPage: (key: string, input: { title: string; body: string; parentId: number | null }) =>
    request<WikiPage>('POST', `/projects/${key}/wiki`, input),
  updateWikiPage: (id: number, input: { title: string; body: string; parentId: number | null; baseVersion: number }) =>
    request<WikiPage>('PUT', `/wiki/${id}`, input),
  deleteWikiPage: (id: number) => request<void>('DELETE', `/wiki/${id}`),
  wikiHistory: (id: number) => request<WikiRevision[]>('GET', `/wiki/${id}/history`),
  wikiRevision: (id: number, version: number) => request<WikiRevision>('GET', `/wiki/${id}/history/${version}`),
  restoreWikiRevision: (id: number, version: number) => request<WikiPage>('POST', `/wiki/${id}/history/${version}/restore`),
  taskWikiMentions: (taskId: number) => request<WikiPageSummary[]>('GET', `/tasks/${taskId}/wiki`),
  meetings: (key: string, sprintId?: number) =>
    request<MeetingNote[]>('GET', `/projects/${key}/meetings${sprintId ? `?sprint=${sprintId}` : ''}`),
  createMeeting: (key: string, input: { kind: MeetingKind; title: string; date: string; body: string; sprintId: number | null }) =>
    request<MeetingNote>('POST', `/projects/${key}/meetings`, input),
  updateMeeting: (id: number, input: { kind: MeetingKind; title: string; date: string; body: string; sprintId: number | null }) =>
    request<MeetingNote>('PUT', `/meetings/${id}`, input),
  deleteMeeting: (id: number) => request<void>('DELETE', `/meetings/${id}`),
  addAction: (noteId: number, text: string, assigneeId: number | null) =>
    request<MeetingNote>('POST', `/meetings/${noteId}/actions`, { text, assigneeId }),
  actionsFromNotes: (noteId: number) => request<MeetingNote>('POST', `/meetings/${noteId}/actions/from-notes`),
  deleteAction: (id: number) => request<MeetingNote>('DELETE', `/meeting-actions/${id}`),
  actionTasks: (noteId: number, only?: number) =>
    request<MeetingNote>('POST', `/meetings/${noteId}/tasks${only ? `?only=${only}` : ''}`),
  standup: (key: string, date?: string) => request<Standup>('GET', `/projects/${key}/standup${date ? `?date=${date}` : ''}`),
  presenceHeartbeat: (taskId: number, clientId: string, editing: boolean) =>
    request<{ user: User; editing: boolean }[]>('POST', `/tasks/${taskId}/presence`, { clientId, editing }),
  presence: (taskId: number) => request<{ user: User; editing: boolean }[]>('GET', `/tasks/${taskId}/presence`),
  presenceLeave: (taskId: number, clientId: string) => request<void>('POST', `/tasks/${taskId}/presence/leave`, { clientId }),
  collabJoin: (taskId: number, clientId: string) =>
    request<{ seed: boolean; updates: string[] }>('POST', `/tasks/${taskId}/collab/join`, { clientId }),
  collabUpdate: (taskId: number, clientId: string, update: string) =>
    request<void>('POST', `/tasks/${taskId}/collab/update`, { clientId, update }),
  collabLeave: (taskId: number, clientId: string) => request<void>('POST', `/tasks/${taskId}/collab/leave`, { clientId }),
  portfolio: () => request<PortfolioRow[]>('GET', '/portfolio'),
  goals: (quarter?: string) => request<Goal[]>('GET', `/goals${quarter ? `?quarter=${encodeURIComponent(quarter)}` : ''}`),
  createGoal: (input: { title: string; description: string; quarter: string; shared: boolean }) =>
    request<Goal>('POST', '/goals', input),
  updateGoal: (id: number, input: { title: string; description: string; quarter: string; shared: boolean }) =>
    request<Goal>('PUT', `/goals/${id}`, input),
  deleteGoal: (id: number) => request<void>('DELETE', `/goals/${id}`),
  addKeyResult: (goalId: number, input: KeyResultInput) => request<Goal>('POST', `/goals/${goalId}/key-results`, input),
  updateKeyResult: (id: number, input: KeyResultInput) => request<Goal>('PUT', `/key-results/${id}`, input),
  deleteKeyResult: (id: number) => request<Goal>('DELETE', `/key-results/${id}`),
  epicGoals: (epicId: number) => request<Goal[]>('GET', `/epics/${epicId}/goals`),
  assign: (id: number, assigneeId: number | null) => request<Task>('PUT', `/tasks/${id}/assignee`, { assigneeId }),
  moveToSprint: (id: number, sprintId: number | null) => request<Task>('PUT', `/tasks/${id}/sprint`, { sprintId }),
  acceptTask: (id: number) => request<Task>('POST', `/tasks/${id}/accept`),
  releaseTask: (id: number) => request<Task>('POST', `/tasks/${id}/release`),
  deleteTask: (id: number) => request<void>('DELETE', `/tasks/${id}`),

  comments: (id: number) => request<Comment[]>('GET', `/tasks/${id}/comments`),
  addComment: (id: number, body: string, anchor?: string | null) =>
    request<Comment>('POST', `/tasks/${id}/comments`, { body, anchor: anchor ?? null }),
  replyToComment: (id: number, parentId: number, body: string) =>
    request<Comment>('POST', `/tasks/${id}/comments`, { body, parentId }),
  react: (id: number, commentId: number, emoji: string) =>
    request<Comment>('PUT', `/tasks/${id}/comments/${commentId}/reactions`, { emoji }),
  cloneTask: (id: number, subtasks: boolean) => request<Task>('POST', `/tasks/${id}/clone`, { subtasks }),
  moveTask: (id: number, projectKey: string) => request<Task>('POST', `/tasks/${id}/move`, { projectKey }),
  templates: (key: string) => request<TaskTemplate[]>('GET', `/projects/${key}/templates`),
  createTemplate: (key: string, input: TemplateInput) => request<TaskTemplate>('POST', `/projects/${key}/templates`, input),
  updateTemplate: (id: number, input: TemplateInput) => request<TaskTemplate>('PUT', `/templates/${id}`, input),
  deleteTemplate: (id: number) => request<void>('DELETE', `/templates/${id}`),
  recurring: (key: string) => request<RecurringTask[]>('GET', `/projects/${key}/recurring`),
  createRecurring: (key: string, input: RecurringInput) => request<RecurringTask>('POST', `/projects/${key}/recurring`, input),
  updateRecurring: (id: number, input: RecurringInput) => request<RecurringTask>('PUT', `/recurring/${id}`, input),
  deleteRecurring: (id: number) => request<void>('DELETE', `/recurring/${id}`),
  runRecurring: (id: number) => request<Task>('POST', `/recurring/${id}/run`),
  editComment: (id: number, commentId: number, body: string) =>
    request<Comment>('PUT', `/tasks/${id}/comments/${commentId}`, { body }),
  deleteComment: (id: number, commentId: number) => request<void>('DELETE', `/tasks/${id}/comments/${commentId}`),
  activity: (id: number) => request<Activity[]>('GET', `/tasks/${id}/activity`),
  links: (id: number) => request<TaskLink[]>('GET', `/tasks/${id}/links`),
  addLink: (id: number, type: LinkType, targetKey: string) =>
    request<TaskLink>('POST', `/tasks/${id}/links`, { type, targetKey }),
  deleteLink: (id: number, linkId: number) => request<void>('DELETE', `/tasks/${id}/links/${linkId}`),
  time: (id: number) => request<TimeEntry[]>('GET', `/tasks/${id}/time`),
  logTime: (id: number, minutes: number, date: string, note: string) =>
    request<TimeEntry>('POST', `/tasks/${id}/time`, { minutes, date, note }),
  deleteTime: (id: number, entryId: number) => request<void>('DELETE', `/tasks/${id}/time/${entryId}`),
  watchers: (id: number) => request<{ watching: boolean; watchers: User[] }>('GET', `/tasks/${id}/watchers`),
  watch: (id: number, watching: boolean) =>
    request<{ watching: boolean; watchers: User[] }>(watching ? 'PUT' : 'DELETE', `/tasks/${id}/watch`),
  devLinks: (id: number) => request<DevLink[]>('GET', `/tasks/${id}/dev`),

  checklist: (id: number) => request<ChecklistItem[]>('GET', `/tasks/${id}/checklist`),
  addChecklistItem: (id: number, text: string) => request<ChecklistItem>('POST', `/tasks/${id}/checklist`, { text }),
  updateChecklistItem: (id: number, itemId: number, change: { text?: string; done?: boolean }) =>
    request<ChecklistItem>('PATCH', `/tasks/${id}/checklist/${itemId}`, change),
  deleteChecklistItem: (id: number, itemId: number) => request<void>('DELETE', `/tasks/${id}/checklist/${itemId}`),

  attachments: (id: number) => request<Attachment[]>('GET', `/tasks/${id}/attachments`),
  uploadAttachment: async (id: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    const response = await send('POST', `/tasks/${id}/attachments`, { body: form });
    return response.json() as Promise<Attachment>;
  },
  /** Attachments need the auth header, so they are fetched as blobs rather than linked directly. */
  attachmentBlob: async (attachmentId: number) => (await send('GET', `/attachments/${attachmentId}/content`)).blob(),
  deleteAttachment: (attachmentId: number) => request<void>('DELETE', `/attachments/${attachmentId}`),

  notifications: (view?: 'inbox' | 'snoozed' | 'done') =>
    request<{ unread: number; items: Notification[] }>('GET', `/notifications${view && view !== 'inbox' ? `?view=${view}` : ''}`),
  triage: (ids: number[], action: 'done' | 'undone' | 'snooze' | 'read', until?: string) =>
    request<void>('POST', '/notifications/triage', { ids, action, until }),
  doneRead: () => request<{ done: number }>('POST', '/notifications/done-read'),
  notificationSettings: () => request<NotificationSettings>('GET', '/notifications/settings'),
  saveNotificationDefaults: (rule: { level: NotificationLevel; email: boolean | null; push: boolean | null }) =>
    request<NotificationSettings>('PUT', '/notifications/settings/defaults', rule),
  projectNotificationRule: (key: string) => request<NotificationRule>('GET', `/notifications/settings/projects/${key}`),
  saveProjectNotificationRule: (key: string, rule: { level: NotificationLevel; email: boolean | null; push: boolean | null }) =>
    request<NotificationSettings>('PUT', `/notifications/settings/projects/${key}`, rule),
  deleteProjectNotificationRule: (key: string) =>
    request<NotificationSettings>('DELETE', `/notifications/settings/projects/${key}`),
  saveQuietHours: (quiet: { timeZone: string | null; from: string | null; to: string | null }) =>
    request<NotificationSettings>('PUT', '/notifications/settings/quiet-hours', quiet),
  reminders: () => request<Reminder[]>('GET', '/reminders'),
  taskReminders: (taskId: number) => request<Reminder[]>('GET', `/tasks/${taskId}/reminders`),
  createReminder: (input: { taskId: number | null; remindAt: string; note: string }) =>
    request<Reminder>('POST', '/reminders', input),
  deleteReminder: (id: number) => request<void>('DELETE', `/reminders/${id}`),
  forecast: (key: string, scope: { release?: number; epic?: number; sprint?: number; items?: number; by?: string } = {}) =>
    request<ForecastResult>('GET', `/projects/${key}/forecast${query(scope)}`),
  burnup: (releaseId: number) => request<Burnup>('GET', `/releases/${releaseId}/burnup`),
  agingWip: (key: string) => request<AgingWip>('GET', `/projects/${key}/aging-wip`),
  bugTrends: (key: string, weeks = 12) => request<BugTrends>('GET', `/projects/${key}/bug-trends?weeks=${weeks}`),
  slaTargets: (key: string) => request<SlaTarget[]>('GET', `/projects/${key}/sla`),
  saveSlaTargets: (key: string, targets: SlaTarget[]) => request<SlaTarget[]>('PUT', `/projects/${key}/sla`, { targets }),
  taskSla: (taskId: number) => request<TaskSla | undefined>('GET', `/tasks/${taskId}/sla`),
  slaReport: (key: string, days = 30) => request<SlaReport>('GET', `/projects/${key}/sla-report?days=${days}`),
  searchTrend: (q: string, weeks = 12) => request<TrendWeek[]>('GET', `/search/trend${query({ q, weeks })}`),
  reportSubscriptions: () => request<ReportSubscription[]>('GET', '/report-subscriptions'),
  createReportSubscription: (input: { kind: ReportKind; target: string; title?: string; frequency: 'DAILY' | 'WEEKLY'; weekday: number; hour: number }) =>
    request<ReportSubscription>('POST', '/report-subscriptions', input),
  updateReportSubscription: (id: number, input: { kind: ReportKind; target: string; title?: string; frequency: 'DAILY' | 'WEEKLY'; weekday: number; hour: number }) =>
    request<ReportSubscription>('PUT', `/report-subscriptions/${id}`, input),
  deleteReportSubscription: (id: number) => request<void>('DELETE', `/report-subscriptions/${id}`),
  previewReport: (id: number) => request<{ subject: string; body: string; sent: boolean; mailEnabled: boolean }>('GET', `/report-subscriptions/${id}/preview`),
  sendReport: (id: number) => request<{ subject: string; body: string; sent: boolean; mailEnabled: boolean }>('POST', `/report-subscriptions/${id}/send`),
  healthChecks: (key: string) => request<HealthCheckSummary[]>('GET', `/projects/${key}/health-checks`),
  createHealthCheck: (key: string, title: string, categories?: string[]) =>
    request<HealthCheckDetail>('POST', `/projects/${key}/health-checks`, { title, categories }),
  healthCheck: (id: number) => request<HealthCheckDetail>('GET', `/health-checks/${id}`),
  voteHealthCheck: (id: number, votes: { category: string; score: number; trend: number }[]) =>
    request<HealthCheckDetail>('PUT', `/health-checks/${id}/votes`, { votes }),
  toggleHealthCheck: (id: number) => request<HealthCheckDetail>('POST', `/health-checks/${id}/close`),
  deleteHealthCheck: (id: number) => request<void>('DELETE', `/health-checks/${id}`),
  timer: () => request<RunningTimer | undefined>('GET', '/timer'),
  startTimer: (taskId: number) =>
    request<{ logged: TimeEntry | null; running: RunningTimer | null }>('POST', '/timer/start', { taskId }),
  stopTimer: (minutes?: number, note?: string) =>
    request<{ logged: TimeEntry | null; running: null }>('POST', '/timer/stop', { minutes, note }),
  discardTimer: () => request<void>('DELETE', '/timer'),
  today: (date?: string) => request<TodayList>('GET', `/today${date ? `?date=${date}` : ''}`),
  pickToday: (taskId: number, date?: string) => request<TodayList>('POST', '/today', { taskId, date }),
  unpickToday: (taskId: number, date?: string) => request<TodayList>('DELETE', `/today/${taskId}${date ? `?date=${date}` : ''}`),
  reorderToday: (taskIds: number[], date?: string) => request<TodayList>('PUT', '/today/order', { taskIds, date }),
  carryOver: (date?: string) => request<TodayList>('POST', `/today/carry-over${date ? `?date=${date}` : ''}`),
  daySummary: (date?: string) => request<DaySummary>('GET', `/today/summary${date ? `?date=${date}` : ''}`),
  personalNotes: (taskId: number) => request<PersonalNotes>('GET', `/tasks/${taskId}/personal`),
  savePersonalNote: (taskId: number, body: string) => request<PersonalNotes>('PUT', `/tasks/${taskId}/personal/note`, { body }),
  addPrivateItem: (taskId: number, text: string) => request<PersonalNotes>('POST', `/tasks/${taskId}/personal/items`, { text }),
  updatePrivateItem: (id: number, change: { text?: string; done?: boolean }) =>
    request<PersonalNotes>('PATCH', `/personal-items/${id}`, change),
  deletePrivateItem: (id: number) => request<PersonalNotes>('DELETE', `/personal-items/${id}`),
  unreadCount: () => request<number>('GET', '/notifications/unread-count'),
  markRead: (id: number) => request<void>('POST', `/notifications/${id}/read`),
  markAllRead: () => request<void>('POST', '/notifications/read-all'),

  profile: () => request<Profile>('GET', '/profile'),
  updateSettings: (settings: { emailFrequency?: EmailFrequency; displayName?: string; language?: string }) =>
    request<Profile>('PUT', '/profile/settings', settings),
  uploadAvatar: async (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return (await send('POST', '/profile/avatar', { body: form })).json() as Promise<User>;
  },
  removeAvatar: () => request<User>('DELETE', '/profile/avatar'),

  pushKey: () => request<{ publicKey: string }>('GET', '/push/key'),
  pushSubscribe: (subscription: PushSubscriptionJSON) => request<void>('POST', '/push/subscribe', subscription),
  pushUnsubscribe: (endpoint: string) => request<void>('DELETE', '/push/subscribe', { endpoint }),
  pushTest: () => request<{ delivered: number }>('POST', '/push/test'),

  invites: () => request<Invite[]>('GET', '/invites'),
  createInvite: (email: string | null, projectKey: string | null, role?: Role) =>
    request<Invite>('POST', '/invites', { email: email || null, projectKey: projectKey || null, role: role ?? null }),
  revokeInvite: (id: number) => request<void>('DELETE', `/invites/${id}`),

  admin: () => request<AdminOverview>('GET', '/admin'),
  setRegistrationMode: (mode: RegistrationMode) =>
    request<AdminOverview>('PUT', '/admin/registration', { mode }),
  approveUser: (id: number) => request<AdminUser>('POST', `/admin/users/${id}/approve`),
  rejectUser: (id: number) => request<void>('DELETE', `/admin/users/${id}`),
  setAdmin: (id: number, admin: boolean) => request<AdminUser>('PUT', `/admin/users/${id}/admin`, { admin }),
  audit: (params: { action?: string; q?: string; page?: number }) =>
    request<AuditPage>('GET', `/admin/audit${query(params)}`),
  resetTwoFactor: (id: number) => request<AdminUser>('POST', `/admin/users/${id}/reset-2fa`),
  requirePasswordChange: (id: number) => request<AdminUser>('POST', `/admin/users/${id}/require-password-change`),
  signOutUser: (id: number) => request<{ signedOut: number }>('POST', `/admin/users/${id}/sign-out`),
  deleteUserAccount: (id: number) => request<void>('DELETE', `/admin/users/${id}/account`),
  adminPasswordPolicy: () => request<PasswordRules>('GET', '/admin/password-policy'),
  setPasswordPolicy: (rules: PasswordRules) => request<PasswordRules>('PUT', '/admin/password-policy', rules),
  backups: () => request<Backup[]>('GET', '/admin/backups'),
  backupNow: () => request<Backup>('POST', '/admin/backups'),
  backupBlob: async (name: string) => (await send('GET', `/admin/backups/${encodeURIComponent(name)}`)).blob(),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('PUT', '/profile/password', { currentPassword, newPassword }),
};

export interface AdminOverview {
  registrationMode: RegistrationMode;
  users: AdminUser[];
  /** Public URL used in email links; learned from admin visits unless APP_BASE_URL is set. */
  siteUrl: string;
  siteUrlConfigured: boolean;
}

export type TemplateInput = Omit<TaskTemplate, 'id'>;

export interface RecurringInput {
  title: string;
  description: string;
  type: TaskType;
  priority: Priority;
  labels: string[];
  checklist: string[];
  assigneeId: number | null;
  frequency: Frequency;
  dayOfWeek: number;
  dayOfMonth: number;
  dueInDays: number | null;
  active: boolean;
}

export interface PasswordRules {
  minLength: number;
  upper: boolean;
  digit: boolean;
  special: boolean;
}

export interface SessionInfo {
  id: string;
  current: boolean;
  createdAt: string;
  lastSeenAt: string;
  ip: string | null;
  device: string | null;
  method: string;
}

export type ChatEvent = 'TASK_CREATED' | 'TASK_DONE' | 'STATUS_CHANGED' | 'COMMENT_ADDED' | 'SPRINT';

export interface ChatHook {
  id: number;
  kind: 'SLACK' | 'DISCORD';
  url: string;
  events: ChatEvent[];
  lastDeliveryAt: string | null;
  lastError: string | null;
}

export interface AuditEntry {
  id: number;
  createdAt: string;
  actor: string | null;
  action: string;
  target: string | null;
  details: string | null;
  ip: string | null;
}

export interface AuditPage {
  items: AuditEntry[];
  total: number;
  page: number;
  pages: number;
  actions: string[];
}

export interface RuleInput {
  name: string;
  trigger: RuleTrigger;
  triggerStatus: Status | null;
  condition: string;
  actions: RuleAction[];
  enabled?: boolean;
}

export interface ReleaseInput {
  name: string;
  description: string;
  releaseDate: string | null;
}

export interface EpicInput {
  name: string;
  description: string;
  startDate: string | null;
  dueDate: string | null;
}

export interface ColumnInput {
  name: string;
  status: Status;
  wipLimit: number | null;
}

/** Saves a blob as a file download. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export interface LiveMessage {
  type: 'task' | 'project' | 'notification' | 'ready' | 'presence' | 'collab' | 'timer' | 'today' | 'reminders';
  data: { projectId?: number; taskId?: number; deleted?: boolean; clientId?: string; update?: string };
}

/**
 * Opens the server-sent event stream. Uses fetch rather than EventSource because the
 * stream needs the Authorization header. Resolves when the stream ends.
 */
export async function streamEvents(onMessage: (message: LiveMessage) => void, signal: AbortSignal) {
  const response = await fetch('/api/events', {
    headers: { Accept: 'text/event-stream', ...authHeaders() },
    signal,
  });
  if (!response.ok || !response.body) throw new ApiError(response.status, 'Live updates unavailable');
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += value;
    let boundary: number;
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      let type = '';
      let data = '';
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) type = line.slice(6).trim();
        else if (line.startsWith('data:')) data += line.slice(5).trim();
      }
      if (type) {
        try {
          onMessage({ type: type as LiveMessage['type'], data: data ? JSON.parse(data) : {} });
        } catch {
          /* ignore malformed event */
        }
      }
    }
  }
}

/** Links people copy use the address this browser reached the app at, so they are right behind any proxy. */
export function inviteLink(code: string) {
  return `${window.location.origin}/register?invite=${encodeURIComponent(code)}`;
}

export function githubWebhookUrl(projectKey: string) {
  return `${window.location.origin}/api/integrations/github/${projectKey}`;
}
