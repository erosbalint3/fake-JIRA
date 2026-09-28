import type {
  Activity, Attachment, Burndown, ChecklistItem, Comment, CreateTaskInput, Notification, Priority, Profile,
  Project, Scope, Sprint, Status, Task, TaskInput, User,
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

interface AuthResponse {
  token: string;
  user: User;
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
  register: (username: string, email: string, password: string) =>
    request<AuthResponse>('POST', '/auth/register', { username, email, password }),
  me: () => request<User>('GET', '/auth/me'),
  forgotPassword: (email: string) => request<void>('POST', '/auth/forgot-password', { email }),
  resetPassword: (token: string, newPassword: string) =>
    request<void>('POST', '/auth/reset-password', { token, newPassword }),

  projects: () => request<Project[]>('GET', '/projects'),
  project: (key: string) => request<Project>('GET', `/projects/${key}`),
  createProject: (key: string, name: string, description: string) =>
    request<Project>('POST', '/projects', { key, name, description }),
  updateProject: (key: string, name: string, description: string) =>
    request<Project>('PUT', `/projects/${key}`, { name, description }),
  deleteProject: (key: string) => request<void>('DELETE', `/projects/${key}`),
  addMember: (key: string, login: string) => request<Project>('POST', `/projects/${key}/members`, { login }),
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

  tasks: (filters: TaskFilters = {}) => request<Task[]>('GET', `/tasks${query({ ...filters })}`),
  task: (id: number) => request<Task>('GET', `/tasks/${id}`),
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
  activity: (id: number) => request<Activity[]>('GET', `/tasks/${id}/activity`),

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
  updateSettings: (emailNotifications: boolean) => request<Profile>('PUT', '/profile/settings', { emailNotifications }),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('PUT', '/profile/password', { currentPassword, newPassword }),
};

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
