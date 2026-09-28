import { Link } from 'react-router-dom';
import { Compass } from 'lucide-react';
import { EmptyState } from '../components/States';

export function NotFoundPage() {
  return (
    <div className="page">
      <EmptyState icon={<Compass size={28} />} title="Page not found">
        <Link to="/backlog">Go to the backlog</Link>
      </EmptyState>
    </div>
  );
}
