import { useCallback, useEffect, useState } from 'react';
import { GitMerge } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { useLiveRefresh } from '../../live';
import type { CustomFieldDef, Workflow } from '../../types';
import { t } from '../../i18n';

const key = (from: number | null, to: number) => `${from ?? 'any'}>${to}`;

/**
 * The board columns are the project's statuses. Here editors choose which moves are allowed (optional) and
 * what a task needs before it may enter each column.
 */
export function WorkflowSection({ projectKey, projectId, canEdit }: { projectKey: string; projectId: number; canEdit: boolean }) {
  const toast = useToast();
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [restricted, setRestricted] = useState(false);
  const [allowed, setAllowed] = useState<Set<string>>(new Set());
  const [required, setRequired] = useState<Record<number, string[]>>({});
  const [fields, setFields] = useState<CustomFieldDef[]>([]);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);

  const apply = (w: Workflow) => {
    setWorkflow(w);
    setRestricted(w.restricted);
    setAllowed(new Set(w.transitions.map((tr) => key(tr.fromId, tr.toId))));
    setRequired(Object.fromEntries(w.columns.map((c) => [c.id, c.required])));
    setDirty(false);
  };
  const load = useCallback(() => {
    api.workflow(projectKey).then(apply).catch(() => {});
    api.customFields(projectKey).then(setFields).catch(() => {});
  }, [projectKey]);
  useEffect(load, [load]);
  useLiveRefresh((m) => m.type === 'project' && m.data.projectId === projectId && !dirty, load, 800);

  if (!workflow) return null;
  const columns = workflow.columns;
  const toggle = (from: number | null, to: number) => {
    const next = new Set(allowed);
    const k = key(from, to);
    if (next.has(k)) next.delete(k);
    else next.add(k);
    setAllowed(next);
    setDirty(true);
  };
  const toggleRequired = (columnId: number, requirement: string) => {
    const list = required[columnId] ?? [];
    setRequired({ ...required, [columnId]: list.includes(requirement) ? list.filter((r) => r !== requirement) : [...list, requirement] });
    setDirty(true);
  };
  const allowAll = () => {
    const next = new Set<string>();
    columns.forEach((from) => columns.forEach((to) => from.id !== to.id && next.add(key(from.id, to.id))));
    setAllowed(next);
    setDirty(true);
  };

  const save = async () => {
    setBusy(true);
    try {
      const transitions = [...allowed].map((k) => {
        const [from, to] = k.split('>');
        return { fromId: from === 'any' ? null : Number(from), toId: Number(to) };
      });
      apply(await api.saveWorkflow(projectKey, {
        restricted, transitions, columns: columns.map((c) => ({ id: c.id, required: required[c.id] ?? [] })),
      }));
      toast(t('Workflow saved'));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const requirementOptions = [
    ...Object.entries(workflow.requirements).map(([id, label]) => ({ id, label })),
    ...fields.map((f) => ({ id: `field:${f.id}`, label: f.name })),
  ];

  return (
    <section className="panel">
      <h2 className="panel-title"><GitMerge size={16} /> {t('Workflow')}</h2>
      <p className="muted small hint">{t('Board columns are this project’s statuses. Choose what a task needs before it enters a column, and optionally which moves are allowed.')}</p>

      <h3 className="subhead">{t('Required to enter a column')}</h3>
      <div className="table-wrap">
        <table className="viz-table workflow-requirements">
          <thead>
            <tr><th>{t('Column')}</th>{requirementOptions.map((o) => <th key={o.id} className="center">{t(o.label)}</th>)}</tr>
          </thead>
          <tbody>
            {columns.map((c) => (
              <tr key={c.id}>
                <th scope="row">{t(c.name)}</th>
                {requirementOptions.map((o) => {
                  const disabled = !canEdit || (o.id === 'resolution' && c.status !== 'DONE');
                  return (
                    <td key={o.id} className="center">
                      <input type="checkbox" disabled={disabled} checked={(required[c.id] ?? []).includes(o.id)}
                        aria-label={t('{column} requires {what}', { column: c.name, what: t(o.label) })}
                        onChange={() => toggleRequired(c.id, o.id)} />
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h3 className="subhead">{t('Allowed moves')}</h3>
      <label className="toggle">
        <input type="checkbox" checked={restricted} disabled={!canEdit} onChange={(e) => {
          setRestricted(e.target.checked);
          setDirty(true);
        }} /> {t('Only allow the moves ticked below')}
      </label>
      {restricted && (
        <>
          <div className="table-wrap">
            <table className="viz-table workflow-matrix">
              <thead>
                <tr><th>{t('From ↓ / to →')}</th>{columns.map((c) => <th key={c.id} className="center">{t(c.name)}</th>)}</tr>
              </thead>
              <tbody>
                {[null, ...columns].map((from) => (
                  <tr key={from?.id ?? 'any'}>
                    <th scope="row">{from ? t(from.name) : t('Any column')}</th>
                    {columns.map((to) => (
                      <td key={to.id} className="center">
                        {from?.id === to.id ? <span className="muted">—</span> : (
                          <input type="checkbox" disabled={!canEdit} checked={allowed.has(key(from?.id ?? null, to.id))}
                            aria-label={t('Allow {from} to {to}', { from: from ? from.name : t('Any column'), to: to.name })}
                            onChange={() => toggle(from?.id ?? null, to.id)} />
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {canEdit && <button className="link small" onClick={allowAll}>{t('Tick every move')}</button>}
        </>
      )}
      {canEdit && (
        <div className="button-row">
          <button className="btn btn-primary" disabled={busy || !dirty} onClick={save}>{t('Save workflow')}</button>
          {dirty && <button className="btn btn-ghost" onClick={load}>{t('Discard changes')}</button>}
        </div>
      )}
    </section>
  );
}
