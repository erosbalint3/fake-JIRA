import { useState, type FormEvent } from 'react';
import { ApiError } from '../api';
import { PRIORITIES, PRIORITY_LABEL, type Priority, type TaskInput } from '../types';
import { Modal } from './Modal';
import { PriorityBadge } from './Badges';

interface Props {
  title: string;
  submitLabel: string;
  initial?: TaskInput;
  onSubmit: (input: TaskInput) => Promise<void>;
  onClose: () => void;
}

export function TaskFormModal({ title, submitLabel, initial, onSubmit, onClose }: Props) {
  const [form, setForm] = useState<TaskInput>(initial ?? { title: '', description: '', priority: 'MEDIUM' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!form.title.trim()) {
      setErrors({ title: 'Title is required' });
      return;
    }
    setBusy(true);
    setErrors({});
    setFormError('');
    try {
      await onSubmit({ ...form, title: form.title.trim() });
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(error.fieldErrors);
        setFormError(error.message);
      }
      setBusy(false);
    }
  };

  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" form="task-form" className="btn btn-primary" disabled={busy}>
            {busy ? 'Saving…' : submitLabel}
          </button>
        </>
      }
    >
      <form id="task-form" className="form" onSubmit={submit} noValidate>
        {formError && !Object.keys(errors).length && <div className="alert">{formError}</div>}
        <label className="field">
          <span>Title</span>
          <input
            value={form.title}
            maxLength={120}
            placeholder="What needs to be done?"
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            aria-invalid={!!errors.title}
          />
          {errors.title && <small className="field-error">{errors.title}</small>}
        </label>
        <label className="field">
          <span>Description</span>
          <textarea
            rows={6}
            value={form.description}
            maxLength={5000}
            placeholder="Add context, acceptance criteria, links…"
            onChange={(e) => setForm({ ...form, description: e.target.value })}
            aria-invalid={!!errors.description}
          />
          {errors.description && <small className="field-error">{errors.description}</small>}
        </label>
        <fieldset className="field">
          <span>Priority</span>
          <div className="segmented">
            {PRIORITIES.map((priority: Priority) => (
              <label key={priority} className={form.priority === priority ? 'active' : ''}>
                <input
                  type="radio"
                  name="priority"
                  value={priority}
                  checked={form.priority === priority}
                  onChange={() => setForm({ ...form, priority })}
                />
                <PriorityBadge priority={priority} compact />
                {PRIORITY_LABEL[priority]}
              </label>
            ))}
          </div>
        </fieldset>
      </form>
    </Modal>
  );
}
