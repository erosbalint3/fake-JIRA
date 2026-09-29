export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Status = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';
export type Scope = 'AVAILABLE' | 'MINE' | 'REPORTED' | 'ALL';
export type SprintState = 'PLANNED' | 'ACTIVE' | 'COMPLETED';

export type Role = 'OWNER' | 'MEMBER' | 'VIEWER';
export type EmailFrequency = 'OFF' | 'INSTANT' | 'DAILY' | 'WEEKLY';
export type RegistrationMode = 'OPEN' | 'INVITE' | 'APPROVAL';
export type LinkType = 'BLOCKS' | 'RELATES' | 'DUPLICATES';

export interface User {
  id: number;
  username: string;
  email: string;
  /** Display name, or the username when none is set. */
  displayName: string;
  avatarUrl: string | null;
}

export interface Member extends User {
  role: Role;
}

export interface Project {
  id: number;
  key: string;
  name: string;
  description: string;
  owner: User;
  members: Member[];
  githubEnabled: boolean;
  githubAutoDone: boolean;
  createdAt: string;
}

export interface SprintRef {
  id: number;
  name: string;
  state: SprintState;
}

export interface Sprint extends SprintRef {
  goal: string;
  startDate: string | null;
  endDate: string | null;
  completedAt: string | null;
  carriedOver: number;
  carriedOverPoints: number;
}

export interface EpicRef {
  id: number;
  name: string;
  colorIndex: number;
}

export interface Epic extends EpicRef {
  description: string;
  startDate: string | null;
  dueDate: string | null;
  taskCount: number;
  doneCount: number;
  points: number;
  donePoints: number;
}

export interface TaskRef {
  id: number;
  key: string;
  title: string;
  status: Status;
  assignee: User | null;
}

export interface BoardColumn {
  id: number;
  name: string;
  status: Status;
  position: number;
  wipLimit: number | null;
}

export interface Task {
  id: number;
  key: string;
  projectId: number;
  projectKey: string;
  projectName: string;
  title: string;
  description: string;
  priority: Priority;
  status: Status;
  reporter: User;
  assignee: User | null;
  sprint: SprintRef | null;
  epic: EpicRef | null;
  parent: TaskRef | null;
  columnId: number | null;
  dueDate: string | null;
  labels: string[];
  storyPoints: number | null;
  checklistTotal: number;
  checklistDone: number;
  subtaskTotal: number;
  subtaskDone: number;
  timeSpentMinutes: number;
  blocked: boolean;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface TaskInput {
  title: string;
  description: string;
  priority: Priority;
  dueDate: string | null;
  labels: string[];
  storyPoints: number | null;
  epicId: number | null;
}

export interface CreateTaskInput extends TaskInput {
  projectKey: string;
  assigneeId: number | null;
  sprintId: number | null;
  parentId?: number | null;
}

export interface BulkChange {
  status?: Status;
  priority?: Priority;
  assigneeId?: number;
  unassign?: boolean;
  sprintId?: number;
  clearSprint?: boolean;
  epicId?: number;
  clearEpic?: boolean;
  addLabels?: string[];
  removeLabels?: string[];
  delete?: boolean;
}

export interface TaskLink {
  id: number;
  type: LinkType;
  label: string;
  task: TaskRef;
}

export interface TimeEntry {
  id: number;
  user: User;
  minutes: number;
  date: string;
  note: string;
  createdAt: string;
}

export interface DevLink {
  id: number;
  kind: 'COMMIT' | 'PULL_REQUEST';
  url: string;
  title: string;
  state: string | null;
  author: string | null;
  updatedAt: string;
}

export interface SavedFilter {
  id: number;
  name: string;
  query: string;
  shared: boolean;
  owner: string;
  mine: boolean;
}

export interface TimeReport {
  from: string;
  to: string;
  totalMinutes: number;
  entries: number;
  byUser: { user: User; minutes: number }[];
  byTask: { task: TaskRef; minutes: number }[];
}

export interface VelocityEntry {
  sprintId: number;
  name: string;
  committedPoints: number;
  completedPoints: number;
  committedTasks: number;
  completedTasks: number;
}

export interface Invite {
  id: number;
  code: string;
  link: string;
  email: string | null;
  projectKey: string | null;
  createdBy: string;
  expiresAt: string;
  usedAt: string | null;
  usedBy: string | null;
}

export interface AdminUser {
  user: User;
  admin: boolean;
  status: 'ACTIVE' | 'PENDING';
  createdAt: string;
  twoFactor: boolean;
  mustChangePassword: boolean;
}

export interface Backup {
  name: string;
  size: number;
  createdAt: string;
}

export interface GithubSettings {
  enabled: boolean;
  webhookUrl: string;
  secret: string | null;
  autoDone: boolean;
}

export interface ImportResult {
  created: number;
  keys: string[];
  errors: { row: number; message: string }[];
}

export interface Comment {
  id: number;
  author: User;
  body: string;
  createdAt: string;
  editedAt: string | null;
}

export interface ChecklistItem {
  id: number;
  text: string;
  done: boolean;
}

export interface Activity {
  id: number;
  actor: User;
  message: string;
  createdAt: string;
}

export interface Attachment {
  id: number;
  filename: string;
  contentType: string;
  size: number;
  uploader: User;
  createdAt: string;
}

export interface Notification {
  id: number;
  message: string;
  taskId: number | null;
  read: boolean;
  createdAt: string;
}

export interface Profile {
  user: User;
  memberSince: string;
  stats: { assigned: number; inProgress: number; done: number; reported: number };
  admin: boolean;
  emailFrequency: EmailFrequency;
  emailAvailable: boolean;
  pushDevices: number;
  twoFactorEnabled: boolean;
  recoveryCodesLeft: number;
  /** False for accounts created with Google/GitHub that never set a password. */
  passwordSet: boolean;
  mustChangePassword: boolean;
  /** Connected sign-in providers, e.g. ["github"]. */
  identities: string[];
}

export interface BurndownPoint {
  date: string;
  remaining: number | null;
  ideal: number;
  remainingPoints: number | null;
  idealPoints: number;
}

export interface Burndown {
  sprint: Sprint;
  total: number;
  done: number;
  totalPoints: number;
  donePoints: number;
  points: BurndownPoint[];
}

export const PRIORITIES: Priority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
export const STATUSES: Status[] = ['TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE'];

export const STATUS_LABEL: Record<Status, string> = {
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  IN_REVIEW: 'In review',
  DONE: 'Done',
};

export const PRIORITY_LABEL: Record<Priority, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  CRITICAL: 'Critical',
};

export const PRIORITY_ORDER: Record<Priority, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

export const LINK_TYPES: { type: LinkType; outward: string }[] = [
  { type: 'BLOCKS', outward: 'blocks' },
  { type: 'RELATES', outward: 'relates to' },
  { type: 'DUPLICATES', outward: 'duplicates' },
];

export const EMAIL_FREQUENCY_LABEL: Record<EmailFrequency, string> = {
  OFF: 'Off',
  INSTANT: 'Right away',
  DAILY: 'Daily digest',
  WEEKLY: 'Weekly digest (Mondays)',
};
