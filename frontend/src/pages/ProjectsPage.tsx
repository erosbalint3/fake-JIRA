import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { FolderPlus, Users } from 'lucide-react';
import { api, ApiError } from '../api';
import { useProjects } from '../projects';
import { useToast } from '../toast';
import { Modal } from '../components/Modal';
import { EmptyState, Spinner } from '../components/States';
import type { ProjectTemplate } from '../types';
import { t } from '../i18n';

export function ProjectsPage() {
  const { projects, refresh } = useProjects();
  const [params, setParams] = useSearchParams();
  const [creating, setCreating] = useState(params.get('new') === '1');

  useEffect(() => {
    if (params.get('new') === '1') {
      setCreating(true);
      setParams({}, { replace: true });
    }
  }, [params, setParams]);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>{t("Projects")}</h1>
          <p className="muted">{t("Each project has its own backlog, board, sprints and members.")}</p>
        </div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>
          <FolderPlus size={18} /> New project
        </button>
      </header>

      {!projects && <Spinner />}
      {projects && projects.length === 0 && (
        <EmptyState icon={<FolderPlus size={28} />} title={t("Create your first project")}>
          Projects group tasks, sprints and people. Give it a short key like <b>{t("WEB")}</b>; tasks will be numbered WEB-1, WEB-2…
          <div><button className="btn btn-primary" onClick={() => setCreating(true)}>{t("New project")}</button></div>
        </EmptyState>
      )}
      {projects && projects.length > 0 && (
        <div className="project-grid">
          {projects.map((project) => (
            <Link key={project.key} to={`/p/${project.key}/board`} className="project-card panel">
              <div className="project-card-head">
                <span className="project-icon">{project.key.slice(0, 2)}</span>
                <div>
                  <strong>{project.name}</strong>
                  <span className="muted small">{project.key}</span>
                </div>
              </div>
              {project.description && <p className="muted project-card-desc">{project.description}</p>}
              <span className="muted small project-card-members"><Users size={14} /> {project.members.length} member{project.members.length === 1 ? '' : 's'}</span>
            </Link>
          ))}
        </div>
      )}

      {creating && <CreateProjectModal onClose={() => setCreating(false)} onCreated={refresh} />}
    </div>
  );
}

function CreateProjectModal({ onClose, onCreated }: { onClose: () => void; onCreated: () => Promise<void> }) {
  const navigate = useNavigate();
  const toast = useToast();
  const [name, setName] = useState('');
  const [key, setKey] = useState('');
  const [keyEdited, setKeyEdited] = useState(false);
  const [description, setDescription] = useState('');
  const [template, setTemplate] = useState('blank');
  const [templates, setTemplates] = useState<ProjectTemplate[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.projectTemplates().then(setTemplates).catch(() => setTemplates([]));
  }, []);

  const suggestKey = (value: string) => {
    const words = value.trim().split(/\s+/).filter(Boolean);
    const guess = words.length > 1 ? words.map((w) => w[0]).join('') : value.replace(/[^A-Za-z0-9]/g, '').slice(0, 4);
    return guess.replace(/[^A-Za-z0-9]/g, '').replace(/^[0-9]+/, '').toUpperCase().slice(0, 10);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    setError('');
    try {
      const project = await api.createProject(key, name, description, template);
      await onCreated();
      toast(`Project ${project.key} created`);
      navigate(`/p/${project.key}/board`);
    } catch (e) {
      if (e instanceof ApiError) {
        setErrors(e.fieldErrors);
        setError(e.message);
      }
      setBusy(false);
    }
  };

  return (
    <Modal title={t("New project")} onClose={onClose} footer={
      <>
        <button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button>
        <button className="btn btn-primary" form="project-form" disabled={busy || !name.trim() || !key}>{t("Create project")}</button>
      </>
    }>
      <form id="project-form" className="form" onSubmit={submit}>
        {error && !Object.keys(errors).length && <div className="alert">{error}</div>}
        <label className="field">
          <span>{t("Name")}</span>
          <input value={name} maxLength={80} placeholder={t("Website redesign")} onChange={(e) => {
            setName(e.target.value);
            if (!keyEdited) setKey(suggestKey(e.target.value));
          }} aria-invalid={!!errors.name} />
          {errors.name && <small className="field-error">{errors.name}</small>}
        </label>
        <label className="field">
          <span>{t("Key")}</span>
          <input value={key} maxLength={10} placeholder={t("WEB")} onChange={(e) => {
            setKeyEdited(true);
            setKey(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''));
          }} aria-invalid={!!errors.key} />
          <small className="muted">Prefix for task keys, e.g. {key || 'WEB'}-1. Cannot be changed later.</small>
          {errors.key && <small className="field-error">{errors.key}</small>}
        </label>
        <label className="field">
          <span>{t("Description")} <span className="muted">{t("(optional)")}</span></span>
          <textarea rows={3} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} />
        </label>
        {templates.length > 0 && (
          <fieldset className="field template-picks">
            <legend>{t("Start from")}</legend>
            {templates.map((t) => (
              <label key={t.id} className={`template-pick ${template === t.id ? 'active' : ''}`}>
                <input type="radio" name="template" value={t.id} checked={template === t.id} onChange={() => setTemplate(t.id)} />
                <strong>{t.name}</strong>
                <span className="muted small">{t.description}</span>
              </label>
            ))}
          </fieldset>
        )}
      </form>
    </Modal>
  );
}
