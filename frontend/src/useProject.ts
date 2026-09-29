import { useParams } from 'react-router-dom';
import { useAuth } from './auth';
import { useProjects } from './projects';
import type { Project, Role } from './types';

/** The project named in the current /p/:key route, if the user is a member. */
export function useRouteProject() {
  const { key } = useParams();
  const { projects, byKey } = useProjects();
  const project = byKey(key);
  const access = useProjectAccess(project);
  return { key: key?.toUpperCase() ?? '', project, loading: projects === null, ...access };
}

/** The current user's role in a project; viewers get read-only screens. */
export function useProjectAccess(project: Project | undefined) {
  const { user } = useAuth();
  const role: Role | null = project?.members.find((m) => m.id === user?.id)?.role ?? null;
  return { role, canEdit: role === 'OWNER' || role === 'MEMBER', isOwner: role === 'OWNER' };
}
