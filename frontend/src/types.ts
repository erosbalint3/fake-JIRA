export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type Status = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';
export type Scope = 'AVAILABLE' | 'MINE' | 'REPORTED' | 'ALL';

export interface User {
  id: number;
  username: string;
  email: string;
}

export interface Task {
  id: number;
  key: string;
  title: string;
  description: string;
  priority: Priority;
  status: Status;
  reporter: User;
  assignee: User | null;
  createdAt: string;
  updatedAt: string;
}

export interface TaskInput {
  title: string;
  description: string;
  priority: Priority;
}

export interface Comment {
  id: number;
  author: User;
  body: string;
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
