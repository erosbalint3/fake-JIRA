import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from './api';
import { useAuth } from './auth';
import { useLiveRefresh } from './live';
import type { Project } from './types';

const LAST_PROJECT = 'fakejira.lastProject';

interface ProjectsState {
  projects: Project[] | null;
  refresh: () => Promise<void>;
  byKey: (key: string | undefined) => Project | undefined;
  lastKey: () => string | null;
  remember: (key: string) => void;
}

const ProjectsContext = createContext<ProjectsState | null>(null);

export function ProjectsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [projects, setProjects] = useState<Project[] | null>(null);

  const refresh = useCallback(async () => {
    try {
      setProjects(await api.projects());
    } catch {
      setProjects((current) => current ?? []);
    }
  }, []);

  useEffect(() => {
    if (user) refresh();
    else setProjects(null);
  }, [user, refresh]);

  useLiveRefresh((message) => message.type === 'project', refresh);

  const value = useMemo<ProjectsState>(() => ({
    projects,
    refresh,
    byKey: (key) => projects?.find((project) => project.key === key?.toUpperCase()),
    lastKey: () => {
      try {
        const key = localStorage.getItem(LAST_PROJECT);
        return key && projects?.some((project) => project.key === key) ? key : projects?.[0]?.key ?? null;
      } catch {
        return projects?.[0]?.key ?? null;
      }
    },
    remember: (key) => {
      try {
        localStorage.setItem(LAST_PROJECT, key);
      } catch {
        /* ignore */
      }
    },
  }), [projects, refresh]);

  return <ProjectsContext.Provider value={value}>{children}</ProjectsContext.Provider>;
}

export function useProjects() {
  const context = useContext(ProjectsContext);
  if (!context) throw new Error('useProjects must be used inside ProjectsProvider');
  return context;
}
