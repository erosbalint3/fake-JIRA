import type {
  Comment, Notification, Priority, Profile, Scope, Status, Task, TaskInput, User,
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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Is the backend running?');
  }

  if (response.status === 401 && token) {
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
    if (response.status === 401 && !token) message = message || 'Please sign in.';
    throw new ApiError(response.status, message, fieldErrors);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

interface AuthResponse {
  token: string;
  user: User;
}

export interface TaskFilters {
  scope?: Scope;
  q?: string;
  priority?: Priority | '';
  status?: Status | '';
}

export const api = {
  login: (login: string, password: string) =>
    request<AuthResponse>('POST', '/auth/login', { login, password }),
  register: (username: string, email: string, password: string) =>
    request<AuthResponse>('POST', '/auth/register', { username, email, password }),
  me: () => request<User>('GET', '/auth/me'),

  tasks: (filters: TaskFilters = {}) => {
    const params = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => {
      if (value) params.set(key, String(value));
    });
    const query = params.toString();
    return request<Task[]>('GET', `/tasks${query ? `?${query}` : ''}`);
  },
  task: (id: number) => request<Task>('GET', `/tasks/${id}`),
  createTask: (input: TaskInput) => request<Task>('POST', '/tasks', input),
  updateTask: (id: number, input: TaskInput) => request<Task>('PUT', `/tasks/${id}`, input),
  setStatus: (id: number, status: Status) => request<Task>('PATCH', `/tasks/${id}/status`, { status }),
  acceptTask: (id: number) => request<Task>('POST', `/tasks/${id}/accept`),
  releaseTask: (id: number) => request<Task>('POST', `/tasks/${id}/release`),
  deleteTask: (id: number) => request<void>('DELETE', `/tasks/${id}`),
  comments: (id: number) => request<Comment[]>('GET', `/tasks/${id}/comments`),
  addComment: (id: number, body: string) => request<Comment>('POST', `/tasks/${id}/comments`, { body }),

  notifications: () => request<{ unread: number; items: Notification[] }>('GET', '/notifications'),
  unreadCount: () => request<number>('GET', '/notifications/unread-count'),
  markRead: (id: number) => request<void>('POST', `/notifications/${id}/read`),
  markAllRead: () => request<void>('POST', '/notifications/read-all'),

  profile: () => request<Profile>('GET', '/profile'),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<void>('PUT', '/profile/password', { currentPassword, newPassword }),
};
