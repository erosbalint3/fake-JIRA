export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Status = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';
export type Scope = 'AVAILABLE' | 'MINE' | 'REPORTED' | 'ALL';
export type SprintState = 'PLANNED' | 'ACTIVE' | 'COMPLETED';

export type Role = 'OWNER' | 'MEMBER' | 'VIEWER' | 'GUEST';
/** Viewers and guests cannot own work. */
export const isReadOnlyRole = (role: Role) => role === 'VIEWER' || role === 'GUEST';
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
  /** Blocked tasks move later automatically when a blocker slips. */
  autoSchedule: boolean;
  /** Only the workflow's transitions are allowed. */
  restrictTransitions: boolean;
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
  /** What a task needs before entering this column (see Requirement). */
  required: string[];
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
  resolution: Resolution | null;
  startDate: string | null;
  estimateMinutes: number | null;
  archivedAt: string | null;
  helpers: User[];
  components: { id: number; name: string }[];
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
  componentIds?: number[];
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
  kind: 'COMMIT' | 'PULL_REQUEST' | 'BRANCH';
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
  /** Role in the project for project invites. */
  role: Role | null;
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
  /** Inline comments: the passage of the description they refer to. */
  anchor: string | null;
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
  snoozedUntil?: string | null;
  doneAt?: string | null;
}

export type NotificationLevel = 'ALL' | 'DIRECT' | 'MUTED';

export interface NotificationRule {
  projectKey: string | null;
  projectName: string | null;
  /** Null on a project that has no rule of its own. */
  level: NotificationLevel | null;
  email: boolean | null;
  push: boolean | null;
}

export interface NotificationSettings {
  defaults: NotificationRule;
  projects: NotificationRule[];
  quietHours: { timeZone: string | null; from: string | null; to: string | null };
}

export interface Reminder {
  id: number;
  task: TaskRef | null;
  note: string;
  remindAt: string;
}

export interface RunningTimer {
  task: TaskRef;
  projectKey: string;
  startedAt: string;
  elapsedSeconds: number;
}

export interface TodayList {
  date: string;
  picks: Task[];
  suggestions: Task[];
  previousDay: string | null;
  carryOver: Task[];
}

export interface DaySummary {
  date: string;
  completed: Task[];
  unfinished: Task[];
  minutesLogged: number;
  comments: number;
  text: string;
}

export interface PersonalNotes {
  note: string;
  updatedAt: string | null;
  items: { id: number; text: string; done: boolean }[];
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
  language: string;
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
  goals: { id: number; text: string; done: boolean }[];
  goalsMet: number;
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

export type HitKind = 'TASK' | 'COMMENT' | 'ATTACHMENT' | 'EPIC' | 'RELEASE' | 'WIKI';

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

export type WidgetType = 'filter' | 'chart' | 'counter' | 'activity' | 'recent' | 'calendar' | 'sprint' | 'trend' | 'report';

export type ReportWidgetKind = 'forecast' | 'aging' | 'bugs' | 'sla';

export interface Widget {
  type: WidgetType;
  title: string;
  query?: string;
  groupBy?: string;
  project?: string;
  limit?: number;
  /** Chart widgets: bars (default) or a donut. */
  chartKind?: 'bars' | 'donut';
  /** Trend widgets: how many weeks back. */
  weeks?: number;
  /** Report widgets: which project report. */
  report?: ReportWidgetKind;
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

export type CustomFieldType = 'TEXT' | 'NUMBER' | 'SELECT' | 'DATE' | 'CHECKBOX' | 'URL';

export interface CustomFieldDef {
  id: number;
  name: string;
  type: CustomFieldType;
  options: string[];
  position: number;
}

export interface CustomFieldValue {
  fieldId: number;
  name: string;
  type: CustomFieldType;
  options: string[];
  value: string | null;
}

export interface ProjectTemplate {
  id: string;
  name: string;
  description: string;
}

export interface StorageUsage {
  usedBytes: number;
  files: number;
  quotaBytes: number | null;
}

export interface SystemInfo {
  version: string;
  startedAt: string;
  uptimeSeconds: number;
  database: { product: string; version: string; url: string; sizeBytes: number | null; ok: boolean };
  disk: { path: string; freeBytes: number; totalBytes: number };
  counts: { users: number; projects: number; tasks: number; openTasks: number; comments: number; attachments: number; attachmentBytes: number };
  jvm: { java: string; heapUsedBytes: number; heapMaxBytes: number; threads: number; processors: number };
  features: { mail: boolean; inboundMail: boolean; offsiteBackups: boolean; metrics: boolean };
  backups: { name: string; size: number; createdAt: string }[];
  offsite: OffsiteStatus;
  update: { enabled: boolean; current: string; latest: string | null; available: boolean; url: string | null; checkedAt: string | null; error: string | null };
  quota: { projectMb: number; totalMb: number };
  storageByProject: { key: string; name: string; bytes: number; files: number }[];
}

export interface OffsiteStatus {
  s3: boolean;
  s3Target: string | null;
  webdav: boolean;
  webdavTarget: string | null;
  lastUploadAt: string | null;
  lastFile: string | null;
  lastError: string | null;
}

// ---------------------------------------------------------------- 5.0: work management

export type Resolution = 'DONE' | 'FIXED' | 'WONT_DO' | 'DUPLICATE' | 'CANNOT_REPRODUCE';
export const RESOLUTIONS: Resolution[] = ['DONE', 'FIXED', 'WONT_DO', 'DUPLICATE', 'CANNOT_REPRODUCE'];
export const RESOLUTION_LABEL: Record<Resolution, string> = {
  DONE: 'Done', FIXED: 'Fixed', WONT_DO: "Won't do", DUPLICATE: 'Duplicate', CANNOT_REPRODUCE: 'Cannot reproduce',
};

export interface WorkflowColumn {
  id: number;
  name: string;
  status: Status;
  required: string[];
}

export interface Workflow {
  restricted: boolean;
  columns: WorkflowColumn[];
  transitions: { fromId: number | null; toId: number }[];
  /** Requirement key → human label. */
  requirements: Record<string, string>;
}

export interface ProjectComponent {
  id: number;
  name: string;
  description: string;
  lead: User | null;
  taskCount: number;
}

export type ApprovalState = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface Approval {
  id: number;
  approver: User;
  requestedBy: User;
  state: ApprovalState;
  request: string;
  decisionNote: string | null;
  createdAt: string;
  decidedAt: string | null;
  canDecide: boolean;
  task: TaskRef;
}

export interface SprintGoal {
  id: number;
  text: string;
  done: boolean;
}

export interface PersonCapacity {
  user: User;
  workingDays: number;
  awayDays: number;
  daysOff: number;
  hoursPerDay: number;
  availableHours: number;
  remainingHours: number;
  tasks: number;
  unestimated: number;
  points: number;
  over: boolean;
}

export interface SprintCapacity {
  start: string;
  end: string;
  datesAssumed: boolean;
  workingDays: number;
  people: PersonCapacity[];
  availableHours: number;
  remainingHours: number;
}

export interface TimelineBar {
  id: number;
  key: string;
  title: string;
  status: Status;
  type: TaskType;
  assignee: User | null;
  epic: EpicRef | null;
  start: string;
  due: string;
  days: number;
  points: number | null;
  critical: boolean;
  slack: number;
  conflict: boolean;
}

export interface Timeline {
  tasks: TimelineBar[];
  dependencies: { from: number; to: number }[];
  criticalPath: number[];
  criticalDays: number;
  unscheduled: number;
  autoSchedule: boolean;
}

export type Risk = 'OK' | 'WATCH' | 'AT_RISK';

export interface PortfolioRow {
  key: string;
  name: string;
  color: string | null;
  kanban: boolean;
  open: number;
  inProgress: number;
  done: number;
  percentDone: number;
  overdue: number;
  unassigned: number;
  sprint: { id: number; name: string; end: string | null; done: number; total: number; percentDone: number;
    percentTime: number; behind: boolean } | null;
  release: { id: number; name: string; date: string | null; open: number; late: boolean } | null;
  lateEpics: number;
  lastActivity: string;
  risk: Risk;
  reasons: string[];
}

export type KeyResultKind = 'MANUAL' | 'EPICS';

export interface KeyResult {
  id: number;
  title: string;
  kind: KeyResultKind;
  startValue: number | null;
  target: number | null;
  current: number | null;
  unit: string | null;
  percent: number;
  epics: { id: number | null; name: string; projectKey: string; done: number; total: number }[];
}

export type GoalHealth = 'none' | 'done' | 'on_track' | 'at_risk' | 'off_track';

export interface Goal {
  id: number;
  title: string;
  description: string;
  quarter: string;
  owner: User;
  shared: boolean;
  canEdit: boolean;
  percent: number;
  expected: number;
  health: GoalHealth;
  keyResults: KeyResult[];
}

export interface KeyResultInput {
  title: string;
  kind: KeyResultKind;
  startValue?: number | null;
  target?: number | null;
  current?: number | null;
  unit?: string | null;
  epicIds?: number[];
}

// ---------------------------------------------------------------- 5.0: collaboration

export interface PollOption {
  text: string;
  votes: number;
  voters: string[];
  mine: boolean;
}

export interface Poll {
  id: number;
  question: string;
  multiple: boolean;
  options: PollOption[];
  voters: number;
  createdBy: User;
  createdAt: string;
  closedAt: string | null;
  canClose: boolean;
}

export interface DecisionEntry {
  id: number;
  text: string;
  context: string;
  task: TaskRef | null;
  decidedBy: User;
  decidedAt: string;
}

export interface KudosEntry {
  id: number;
  from: User;
  to: User;
  message: string;
  emoji: string;
  task: TaskRef | null;
  projectKey: string;
  createdAt: string;
}

export interface KudosWall {
  recent: KudosEntry[];
  thisMonth: { user: User; count: number }[];
}

export interface WikiPageSummary {
  id: number;
  title: string;
  slug: string;
  parentId: number | null;
  updatedBy: User;
  updatedAt: string;
}

export interface WikiPage {
  id: number;
  projectKey: string;
  title: string;
  slug: string;
  body: string;
  version: number;
  parentId: number | null;
  path: WikiPageSummary[];
  children: WikiPageSummary[];
  createdBy: User;
  createdAt: string;
  updatedBy: User;
  updatedAt: string;
  canEdit: boolean;
}

export interface WikiRevision {
  version: number;
  title: string;
  author: User;
  createdAt: string;
  body: string | null;
}

export type MeetingKind = 'STANDUP' | 'PLANNING' | 'REVIEW' | 'RETRO' | 'OTHER';

export interface MeetingNote {
  id: number;
  kind: MeetingKind;
  title: string;
  date: string;
  body: string;
  sprintId: number | null;
  sprintName: string | null;
  createdBy: User;
  updatedAt: string;
  actions: { id: number; text: string; assignee: User | null; task: TaskRef | null }[];
}

export interface StandupPerson {
  user: User;
  away: boolean;
  finished: TaskRef[];
  workedOn: TaskRef[];
  today: TaskRef[];
  blocked: TaskRef[];
  minutesLogged: number;
}

export interface Standup {
  date: string;
  since: string;
  people: StandupPerson[];
}

// ---- Reporting ----

export interface ForecastResult {
  scope: string;
  remaining: number;
  weeklyThroughput: number[];
  enoughData: boolean;
  completion: { confidence: number; weeks: number; date: string }[];
  histogram: Record<string, number>;
  targetDate: string | null;
  targetProbability: number | null;
  byTarget: { confidence: number; items: number }[];
}

export interface Burnup {
  releaseId: number;
  name: string;
  releaseDate: string | null;
  days: { date: string; scope: number; done: number; scopePoints: number; donePoints: number }[];
  projectedDate: string | null;
  dailyRate: number;
}

export interface AgingItem {
  task: TaskRef;
  status: Status;
  column: string | null;
  assignee: User | null;
  startedAt: string;
  ageDays: number;
  level: 'ok' | 'watch' | 'late';
}

export interface AgingWip {
  cycleP50: number | null;
  cycleP85: number | null;
  items: AgingItem[];
}

export interface BugTrends {
  weeks: { weekStart: string; created: number; resolved: number; open: number }[];
  openByPriority: Record<Priority, number>;
  resolutions: Partial<Record<Resolution, number>>;
  meanDaysToResolve: number | null;
  oldestOpen: TaskRef[];
}

export interface SlaTarget {
  priority: Priority;
  responseHours: number | null;
  resolveHours: number | null;
}

export type SlaState = 'ok' | 'at_risk' | 'breached' | 'met' | null;

export interface TaskSla {
  priority: Priority;
  responseDueAt: string | null;
  respondedAt: string | null;
  responseState: SlaState;
  resolveDueAt: string | null;
  resolvedAt: string | null;
  resolveState: SlaState;
}

export interface SlaReport {
  days: number;
  priorities: {
    priority: Priority; responseHours: number | null; resolveHours: number | null; tasks: number;
    responseMet: number; responseBreached: number; resolveMet: number; resolveBreached: number;
    averageResponseHours: number | null; averageResolveHours: number | null;
  }[];
  responseMetPercent: number | null;
  resolveMetPercent: number | null;
  attention: { task: TaskRef; priority: Priority; kind: 'response' | 'resolution'; state: SlaState; dueAt: string }[];
}

export interface TrendWeek {
  weekStart: string;
  created: number;
  resolved: number;
  open: number;
}

export type ReportKind = 'filter' | 'project' | 'dashboard';

export interface ReportSubscription {
  id: number;
  kind: ReportKind;
  target: string;
  title: string;
  frequency: 'DAILY' | 'WEEKLY';
  weekday: number;
  hour: number;
  lastSentAt: string | null;
  nextSendAt: string;
}

export interface HealthCheckSummary {
  id: number;
  title: string;
  createdBy: User;
  createdAt: string;
  closed: boolean;
  voters: number;
  categories: string[];
  averages: Record<string, number | null>;
}

export interface HealthCheckDetail {
  check: HealthCheckSummary;
  resultsVisible: boolean;
  results: { category: string; red: number; amber: number; green: number; worse: number; stable: number; better: number; average: number | null }[];
  mine: { category: string; score: number; trend: number }[];
  canManage: boolean;
  members: number;
}

// ---- Service desk ----

export type FormFieldKind = 'text' | 'textarea' | 'select' | 'number' | 'date' | 'checkbox' | 'url';

export interface FormField {
  id: string;
  label: string;
  kind: FormFieldKind;
  required: boolean;
  help?: string;
  options?: string[];
}

export interface RequestTypeDef {
  id: number;
  name: string;
  description: string;
  taskType: TaskType;
  priority: Priority;
  fields: FormField[];
}

export interface ServiceDeskSettings {
  portalEnabled: boolean;
  intro: string;
  roadmapPublic: boolean;
  changelogPublic: boolean;
  portalUrl: string;
  roadmapUrl: string;
  changelogUrl: string;
  widgetSnippet: string;
  requestTypes: RequestTypeDef[];
}

export interface PortalConversation {
  requesterName: string;
  requesterEmail: string;
  requestType: string | null;
  channel: 'portal' | 'widget';
  answers: { label: string; value: string }[];
  trackingUrl: string;
  createdAt: string;
  messages: { id: number; fromRequester: boolean; author: User | null; body: string; createdAt: string }[];
  mailEnabled: boolean;
}

export interface SimilarTask {
  task: TaskRef;
  score: number;
  done: boolean;
}

export interface PublicPortal {
  projectKey: string;
  projectName: string;
  color: string | null;
  intro: string;
  requestTypes: { id: number; name: string; description: string; fields: FormField[] }[];
  roadmap: boolean;
  changelog: boolean;
}

export interface PublicTracking {
  reference: string;
  title: string;
  projectName: string;
  projectKey: string;
  status: string;
  resolved: boolean;
  requestType: string | null;
  createdAt: string;
  messages: { fromRequester: boolean; author: string; body: string; createdAt: string }[];
}

export interface PublicRoadmap {
  projectName: string;
  projectKey: string;
  portal: boolean;
  items: { name: string; description: string; stage: 'now' | 'next' | 'later' | 'done'; startDate: string | null;
    dueDate: string | null; percentDone: number; colorIndex: number }[];
}

export interface PublicChangelog {
  projectName: string;
  projectKey: string;
  portal: boolean;
  releases: { version: string; date: string | null; description: string; features: string[]; fixes: string[]; other: string[] }[];
}

// ---- Integrations ----

export type BuildState = 'pending' | 'running' | 'success' | 'failure' | 'cancelled';

export interface BuildInfo {
  source: string;
  name: string;
  state: BuildState;
  url: string | null;
  ref: string | null;
  updatedAt: string;
}

export interface TaskGithub {
  repo: string | null;
  canCreate: boolean;
  issueUrl: string | null;
  issueNumber: number | null;
  builds: BuildInfo[];
}

export interface GithubRepoSettings {
  repo: string;
  hasToken: boolean;
  defaultBranch: string | null;
  issueSync: boolean;
}

export interface ChatCommandSettings {
  slackSigningSecret: boolean;
  slackBotToken: boolean;
  mattermostToken: boolean;
  discordPublicKey: boolean;
  slackCommandUrl: string;
  slackEventsUrl: string;
  mattermostCommandUrl: string;
  discordInteractionsUrl: string;
}

export interface CalendarStatus {
  available: boolean;
  connected: boolean;
  lastSyncAt: string | null;
  lastError: string | null;
  events: number;
}

export interface LinkPreviewData {
  url: string;
  title: string | null;
  description: string | null;
  image: string | null;
  siteName: string | null;
  embed: string | null;
  kind: string;
}

// ---- Claude assistant ----------------------------------------------------------------------------------------------

export interface AiStatus { enabled: boolean; model: string | null }
export interface AiTaskDraft {
  title: string; description: string; type: TaskType; priority: Priority; labels: string[]; storyPoints: number;
  checklist: string[];
}
export interface AiThreadSummary { summary: string; decisions: string[]; openQuestions: string[]; changes: string[] }
export interface AiProposedTask { title: string; description: string; type: TaskType; priority: Priority; storyPoints: number }
export interface AiNotes { markdown: string }
export interface AiFql { fql: string; explanation: string; total: number }
export interface AiEstimate {
  storyPoints: number; estimateHours: number | null; confidence: 'low' | 'medium' | 'high'; reasoning: string;
  similar: { task: TaskRef; storyPoints: number | null; estimateMinutes: number | null; loggedMinutes: number }[];
}
export interface AiTriage {
  type: TaskType; priority: Priority; assignee: User | null; labels: string[]; duplicateOf: TaskRef | null; reasoning: string;
}
