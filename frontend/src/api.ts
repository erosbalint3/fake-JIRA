import type {
  Activity, AdminUser, Attachment, Backup, BoardColumn, BulkChange, Burndown, ChecklistItem, Comment,
  CreateTaskInput, DevLink, EmailFrequency, Epic, GithubSettings, ImportResult, Invite, LinkType, Notification,
  Priority, Profile, Project, RegistrationMode, Role, SavedFilter, Scope, Sprint, Status, Task, TaskInput,
  TaskLink, TimeEntry, TimeReport, User, VelocityEntry, TaskTemplate, RecurringTask, TaskType, Frequency,
  Release, RetroItem, RetroKind, SprintReview, PokerState, FlowDay, CycleReport, Throughput,
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
    throw new ApiError(0, 'Cannot reach the server. Is the backend running?');
  }
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
  me: () => request<{ user: User; admin: boolean; mustChangePassword: boolean }>('GET', '/auth/me'),
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
  createProject: (key: string, name: string, description: string) =>
    request<Project>('POST', '/projects', { key, name, description }),
  updateProject: (key: string, name: string, description: string, extra: { kanban?: boolean; color?: string } = {}) =>
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

  exportCsv: async (key: string) => (await send('GET', `/projects/${key}/export.csv`)).blob(),
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
  moveToColumn: (id: number, columnId: number) => request<Task>('PATCH', `/tasks/${id}/column`, { columnId }),
  createTask: (input: CreateTaskInput) => request<Task>('POST', '/tasks', input),
  updateTask: (id: number, input: TaskInput) => request<Task>('PUT', `/tasks/${id}`, input),
  setStatus: (id: number, status: Status) => request<Task>('PATCH', `/tasks/${id}/status`, { status }),
  assign: (id: number, assigneeId: number | null) => request<Task>('PUT', `/tasks/${id}/assignee`, { assigneeId }),
  moveToSprint: (id: number, sprintId: number | null) => request<Task>('PUT', `/tasks/${id}/sprint`, { sprintId }),
  acceptTask: (id: number) => request<Task>('POST', `/tasks/${id}/accept`),
  releaseTask: (id: number) => request<Task>('POST', `/tasks/${id}/release`),
  deleteTask: (id: number) => request<void>('DELETE', `/tasks/${id}`),

  comments: (id: number) => request<Comment[]>('GET', `/tasks/${id}/comments`),
  addComment: (id: number, body: string) => request<Comment>('POST', `/tasks/${id}/comments`, { body }),
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

  notifications: () => request<{ unread: number; items: Notification[] }>('GET', '/notifications'),
  unreadCount: () => request<number>('GET', '/notifications/unread-count'),
  markRead: (id: number) => request<void>('POST', `/notifications/${id}/read`),
  markAllRead: () => request<void>('POST', '/notifications/read-all'),

  profile: () => request<Profile>('GET', '/profile'),
  updateSettings: (settings: { emailFrequency?: EmailFrequency; displayName?: string }) =>
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
  createInvite: (email: string | null, projectKey: string | null) =>
    request<Invite>('POST', '/invites', { email: email || null, projectKey: projectKey || null }),
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
  type: 'task' | 'project' | 'notification' | 'ready';
  data: { projectId?: number; taskId?: number; deleted?: boolean };
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
