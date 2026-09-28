import { useParams } from 'react-router-dom';
import { useProjects } from './projects';

/** The project named in the current /p/:key route, if the user is a member. */
export function useRouteProject() {
  const { key } = useParams();
  const { projects, byKey } = useProjects();
  return { key: key?.toUpperCase() ?? '', project: byKey(key), loading: projects === null };
}
