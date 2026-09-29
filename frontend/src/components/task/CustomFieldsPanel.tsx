import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../../api';
import { useLiveRefresh } from '../../live';
import { useToast } from '../../toast';
import type { CustomFieldValue } from '../../types';

/** The project's custom fields for one task, edited in place (saved on change / blur). */
export function CustomFieldsPanel({ taskId, canEdit }: { taskId: number; canEdit: boolean }) {
  const toast = useToast();
  const [fields, setFields] = useState<CustomFieldValue[]>([]);

  const load = useCallback(() => {
    api.taskFields(taskId).then(setFields).catch(() => setFields([]));
  }, [taskId]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'task' && m.data.taskId === taskId, load, 300);

  if (fields.length === 0) return null;

  const save = async (field: CustomFieldValue, value: string | null) => {
    if ((field.value ?? '') === (value ?? '')) return;
    try {
      const saved = await api.setTaskField(taskId, field.fieldId, value);
      setFields((list) => list.map((f) => (f.fieldId === field.fieldId ? saved : f)));
      toast(`${field.name} updated`);
    } catch (e) {
      const err = e as ApiError;
      toast(err.fieldErrors?.value ?? err.message, 'error');
      load();
    }
  };

  return (
    <dl className="props custom-props">
      {fields.map((f) => (
        <div key={f.fieldId} className="prop-row">
          <dt>{f.name}</dt>
          <dd>
            {f.type === 'SELECT' ? (
              <select aria-label={f.name} disabled={!canEdit} value={f.value ?? ''} onChange={(e) => save(f, e.target.value || null)}>
                <option value="">—</option>
                {f.options.map((o) => <option key={o} value={o}>{o}</option>)}
              </select>
            ) : f.type === 'CHECKBOX' ? (
              <input type="checkbox" aria-label={f.name} disabled={!canEdit} checked={f.value === 'true'}
                onChange={(e) => save(f, e.target.checked ? 'true' : null)} />
            ) : f.type === 'URL' && !canEdit && f.value ? (
              <a href={f.value} target="_blank" rel="noreferrer noopener">{f.value}</a>
            ) : (
              <input aria-label={f.name} disabled={!canEdit} key={`${f.fieldId}-${f.value}`} defaultValue={f.value ?? ''}
                type={f.type === 'DATE' ? 'date' : f.type === 'NUMBER' ? 'number' : f.type === 'URL' ? 'url' : 'text'}
                placeholder="—" maxLength={500}
                onBlur={(e) => save(f, e.target.value.trim() || null)}
                onChange={(e) => f.type === 'DATE' && save(f, e.target.value || null)}
                onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()} />
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}
