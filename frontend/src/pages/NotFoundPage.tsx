import { Link } from 'react-router-dom';
import { Compass } from 'lucide-react';
import { EmptyState } from '../components/States';
import { t } from '../i18n';

export function NotFoundPage() {
  return (
    <div className="page">
      <EmptyState icon={<Compass size={28} />} title={t("Page not found")}>
        <Link to="/">{t("Go to your board")}</Link>
      </EmptyState>
    </div>
  );
}
