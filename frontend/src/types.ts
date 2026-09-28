export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Status = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';
export type Scope = 'AVAILABLE' | 'MINE' | 'REPORTED' | 'ALL';
export type SprintState = 'PLANNED' | 'ACTIVE' | 'COMPLETED';

export interface User {
  id: number;
  username: string;
  email: string;
}

export interface Project {
  id: number;
  key: string;
  name: string;
  description: string;
  owner: User;
  members: User[];
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
  dueDate: string | null;
  labels: string[];
  checklistTotal: number;
  checklistDone: number;
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
}

export interface CreateTaskInput extends TaskInput {
  projectKey: string;
  assigneeId: number | null;
  sprintId: number | null;
}

export interface Comment {
  id: number;
  author: User;
  body: string;
  createdAt: string;
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
  emailNotifications: boolean;
  emailAvailable: boolean;
}

export interface BurndownPoint {
  date: string;
  remaining: number | null;
  ideal: number;
}

export interface Burndown {
  sprint: Sprint;
  total: number;
  done: number;
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
