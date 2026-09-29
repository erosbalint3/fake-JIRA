export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Status = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';
export type Scope = 'AVAILABLE' | 'MINE' | 'REPORTED' | 'ALL';
export type SprintState = 'PLANNED' | 'ACTIVE' | 'COMPLETED';

export type Role = 'OWNER' | 'MEMBER' | 'VIEWER';
export type TaskType = 'TASK' | 'BUG' | 'STORY' | 'SPIKE';
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
  /** Last day of the person's out-of-office; set only while they are away today. */
  awayUntil?: string | null;
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
  /** Kanban projects have no sprints: the board shows every task. */
  kanban: boolean;
  /** Accent colour (#rrggbb) or null for the default. */
  color: string | null;
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
  /** Epics that must finish before this one starts. */
  dependsOn: number[];
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
  type: TaskType;
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
  release: ReleaseRef | null;
}

export interface TaskInput {
  title: string;
  description: string;
  priority: Priority;
  dueDate: string | null;
  labels: string[];
  storyPoints: number | null;
  epicId: number | null;
  type: TaskType;
}

export interface CreateTaskInput extends TaskInput {
  projectKey: string;
  assigneeId: number | null;
  sprintId: number | null;
  parentId?: number | null;
  checklist?: string[];
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
  type?: TaskType;
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
  gitlabUrl: string;
  giteaUrl: string;
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
  parentId: number | null;
  reactions: Reaction[];
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
  /** Text before/after a description change. */
  before: string | null;
  after: string | null;
}

export interface Reaction {
  emoji: string;
  count: number;
  mine: boolean;
  users: string[];
}

export interface TaskTemplate {
  id: number;
  name: string;
  type: TaskType;
  title: string;
  description: string;
  priority: Priority;
  labels: string[];
  checklist: string[];
  storyPoints: number | null;
}

export type Frequency = 'DAILY' | 'WEEKDAYS' | 'WEEKLY' | 'MONTHLY';

export interface RecurringTask {
  id: number;
  title: string;
  description: string;
  type: TaskType;
  priority: Priority;
  labels: string[];
  checklist: string[];
  assignee: User | null;
  frequency: Frequency;
  dayOfWeek: number;
  dayOfMonth: number;
  dueInDays: number | null;
  nextRun: string;
  active: boolean;
  lastTaskKey: string | null;
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
  away: { from: string | null; until: string | null; message: string | null };
  calendarFeed: boolean;
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
  /** Tasks added to or removed from the sprint after it started. */
  changes: ScopeChange[];
}

export interface ScopeChange {
  date: string;
  key: string;
  title: string;
  points: number | null;
  added: boolean;
  actor: string | null;
}

export interface ReleaseRef {
  id: number;
  name: string;
  released: boolean;
}

export interface Release {
  id: number;
  name: string;
  description: string;
  releaseDate: string | null;
  released: boolean;
  releasedAt: string | null;
  taskCount: number;
  doneCount: number;
  points: number;
  donePoints: number;
}

export type RetroKind = 'WENT_WELL' | 'TO_IMPROVE' | 'ACTION';

export interface RetroItem {
  id: number;
  kind: RetroKind;
  text: string;
  author: User;
  votes: number;
  voted: boolean;
  mine: boolean;
  taskId: number | null;
  taskKey: string | null;
  createdAt: string;
}

export interface ReviewTask {
  id: number;
  key: string;
  title: string;
  type: TaskType | null;
  status: Status | null;
  points: number | null;
  assignee: User | null;
}

export interface SprintReview {
  sprint: Sprint;
  committedPoints: number;
  completedPoints: number;
  completedTasks: number;
  completed: ReviewTask[];
  unfinished: ReviewTask[];
  added: ReviewTask[];
  removed: ReviewTask[];
  people: { user: User; tasks: number; points: number }[];
  markdown: string;
}

export interface PokerState {
  active: boolean;
  revealed: boolean;
  startedBy: string | null;
  startedAt: string | null;
  deck: string[];
  myVote: string | null;
  votes: { user: User; voted: boolean; value: string | null }[];
  average: number | null;
  suggestion: string | null;
  consensus: boolean;
}

export interface FlowDay {
  date: string;
  todo: number;
  inProgress: number;
  inReview: number;
  done: number;
}

export interface CycleTask {
  id: number;
  key: string;
  title: string;
  type: TaskType;
  completedAt: string;
  leadDays: number;
  cycleDays: number | null;
}

export interface CycleReport {
  count: number;
  leadAverage: number | null;
  leadP50: number | null;
  leadP85: number | null;
  cycleAverage: number | null;
  cycleP50: number | null;
  cycleP85: number | null;
  tasks: CycleTask[];
}

export interface Throughput {
  weekStart: string;
  tasks: number;
  points: number;
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

export const TASK_TYPES: TaskType[] = ['TASK', 'BUG', 'STORY', 'SPIKE'];
export const TASK_TYPE_LABEL: Record<TaskType, string> = { TASK: 'Task', BUG: 'Bug', STORY: 'Story', SPIKE: 'Spike' };
export const REACTIONS = ['👍', '🎉', '❤️', '😄', '👀', '✅'];

export interface SearchResult {
  total: number;
  truncated: boolean;
  tasks: Task[];
}

export interface SearchGroup {
  key: string;
  label: string;
  count: number;
  points: number;
  done: number;
}

export interface SearchField {
  name: string;
  hint: string;
  values: string[];
}

export type HitKind = 'TASK' | 'COMMENT' | 'ATTACHMENT' | 'EPIC' | 'RELEASE';

export interface TextHit {
  kind: HitKind;
  id: number;
  taskId: number | null;
  key: string | null;
  title: string;
  snippet: string | null;
  projectKey: string;
}

export interface Team {
  id: number;
  name: string;
  handle: string;
  description: string;
  members: User[];
  member: boolean;
  canEdit: boolean;
}

export type WidgetType = 'filter' | 'chart' | 'counter' | 'activity' | 'recent' | 'calendar' | 'sprint';

export interface Widget {
  type: WidgetType;
  title: string;
  query?: string;
  groupBy?: string;
  project?: string;
  limit?: number;
}

export interface Dashboard {
  id: number;
  name: string;
  widgets: Widget[];
}

export interface RecentTask {
  id: number;
  key: string;
  title: string;
  status: Status;
  type: TaskType;
  projectKey: string;
  viewedAt: string;
}

export type CalendarKind = 'TASK' | 'SPRINT' | 'RELEASE' | 'EPIC' | 'AWAY';

export interface CalendarEvent {
  kind: CalendarKind;
  id: number;
  title: string;
  start: string;
  end: string;
  projectKey: string | null;
  key: string | null;
  status: string | null;
  priority: Priority | null;
  type: TaskType | null;
  colorIndex: number | null;
  person: User | null;
}

export interface FeedItem {
  kind: 'change' | 'comment';
  id: number;
  actor: User;
  message: string;
  body: string | null;
  createdAt: string;
  task: { id: number; key: string; title: string; projectKey: string };
}

export type RuleTrigger = 'CREATED' | 'UPDATED' | 'STATUS_CHANGED' | 'ASSIGNED' | 'COMMENTED' | 'SCHEDULED';
export type RuleActionType = 'set_status' | 'set_priority' | 'add_label' | 'remove_label' | 'set_due_in_days' | 'assign'
  | 'comment' | 'notify' | 'move_to_active_sprint';

export interface RuleAction {
  type: RuleActionType;
  value?: string;
}

export interface AutomationRule {
  id: number;
  name: string;
  enabled: boolean;
  trigger: RuleTrigger;
  triggerStatus: Status | null;
  condition: string;
  actions: RuleAction[];
  owner: User;
  lastRunAt: string | null;
  runCount: number;
  lastError: string | null;
  mine: boolean;
}

export interface RuleRun {
  id: number;
  taskId: number;
  taskKey: string;
  ranAt: string;
  success: boolean;
  message: string;
}

export interface OutgoingWebhook {
  id: number;
  url: string;
  events: string[];
  enabled: boolean;
  createdAt: string;
  lastDeliveryAt: string | null;
  lastStatus: number | null;
  lastError: string | null;
  /** Only right after creating it or rotating the secret. */
  secret: string | null;
}

export interface WebhookDelivery {
  id: number;
  event: string;
  sentAt: string;
  status: number | null;
  attempts: number;
  durationMs: number;
  error: string | null;
  payload: string;
}

export interface ApiTokenInfo {
  id: number;
  name: string;
  prefix: string;
  scope: 'READ' | 'WRITE';
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  expired: boolean;
  /** The secret, only in the response to creating it. */
  token: string | null;
}

export interface ShareLinkInfo {
  id: number;
  url: string;
  includeComments: boolean;
  createdAt: string;
  expiresAt: string | null;
  expired: boolean;
  views: number;
  createdBy: string;
}

export interface PublicTask {
  key: string;
  title: string;
  description: string;
  status: Status;
  priority: Priority;
  type: TaskType;
  projectName: string;
  assignee: string | null;
  dueDate: string | null;
  labels: string[];
  storyPoints: number | null;
  checklist: { text: string; done: boolean }[];
  subtasks: { key: string; title: string; status: Status }[];
  comments: { author: string; body: string; createdAt: string }[];
  updatedAt: string;
  sharedUntil: string | null;
}
