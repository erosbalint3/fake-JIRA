import { Link } from 'react-router-dom';
import { Compass } from 'lucide-react';
import { EmptyState } from '../components/States';

export function NotFoundPage() {
  return (
    <div className="page">
      <EmptyState icon={<Compass size={28} />} title="Page not found">
        <Link to="/">Go to your board</Link>
      </EmptyState>
    </div>
  );
}
