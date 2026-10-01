import { useEffect, useState } from 'react';
import { ListChecks } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { TASK_TYPES, TASK_TYPE_LABEL, type TaskType } from '../../types';
import { TypeIcon } from '../Badges';
import { t } from '../../i18n';

/** A default checklist per task type, e.g. every bug gets "Repro steps / Fix / Test / Release notes". */
export function TypeChecklistsSection({ projectKey, canEdit }: { projectKey: string; canEdit: boolean }) {
  const toast = useToast();
  const [lists, setLists] = useState<Record<TaskType, string> | null>(null);
  const [saved, setSaved] = useState<Record<TaskType, string> | null>(null);

  useEffect(() => {
    api.typeChecklists(projectKey).then((data) => {
      const text = Object.fromEntries(Object.entries(data).map(([k, v]) => [k, v.join('\n')])) as Record<TaskType, string>;
      setLists(text);
      setSaved(text);
    }).catch(() => {});
  }, [projectKey]);

  if (!lists || !saved) return null;
  const save = async (type: TaskType) => {
    try {
      const items = await api.saveTypeChecklist(projectKey, type, lists[type].split('\n').map((s) => s.trim()).filter(Boolean));
      const text = items.join('\n');
      setLists({ ...lists, [type]: text });
      setSaved({ ...saved, [type]: text });
      toast(t('Checklist for {type} saved', { type: t(TASK_TYPE_LABEL[type]) }));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><ListChecks size={16} /> {t('Checklists by type')}</h2>
      <p className="muted small hint">{t('New tasks of a type start with its checklist (unless a template gives them one). One item per line.')}</p>
      <div className="type-checklists">
        {TASK_TYPES.map((type) => (
          <label key={type} className="field">
            <span><TypeIcon type={type} /> {t(TASK_TYPE_LABEL[type])}</span>
            <textarea rows={4} value={lists[type]} disabled={!canEdit} placeholder={type === 'BUG' ? t('Repro steps\nFix\nTest\nRelease notes') : ''}
              onChange={(e) => setLists({ ...lists, [type]: e.target.value })} />
            {canEdit && lists[type] !== saved[type] && (
              <button type="button" className="btn btn-soft btn-sm" onClick={() => save(type)}>{t('Save')}</button>
            )}
          </label>
        ))}
      </div>
    </section>
  );
}
