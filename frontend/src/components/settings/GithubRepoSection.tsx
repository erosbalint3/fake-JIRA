import { useEffect, useState, type FormEvent } from 'react';
import { GitFork, Hammer } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import type { GithubRepoSettings } from '../../types';
import { t } from '../../i18n';

/** The GitHub repository behind the project (branches, pull requests, issue sync) and the generic CI endpoint. */
export function GithubRepoSection({ projectKey }: { projectKey: string }) {
  const toast = useToast();
  const [settings, setSettings] = useState<GithubRepoSettings | null | undefined>(undefined);
  const [repo, setRepo] = useState('');
  const [token, setToken] = useState('');
  const [issueSync, setIssueSync] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [secret, setSecret] = useState<string | null>(null);

  useEffect(() => {
    api.github(projectKey).then((g) => setSecret(g.secret)).catch(() => setSecret(null));
    api.githubRepo(projectKey).then((s) => {
      setSettings(s ?? null);
      setRepo(s?.repo ?? '');
      setIssueSync(s?.issueSync ?? false);
    }).catch(() => setSettings(null));
  }, [projectKey]);

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const next = await api.saveGithubRepo(projectKey, { repo: repo.trim(), issueSync, ...(token ? { token } : {}) });
      setSettings(next);
      setToken('');
      toast(t('Connected to {repo}', { repo: next.repo }));
    } catch (e) {
      const err = e as ApiError;
      setError(err.fieldErrors?.repo ?? err.fieldErrors?.token ?? err.message);
    } finally {
      setBusy(false);
    }
  };

  const importIssues = async (state: 'open' | 'all') => {
    setBusy(true);
    try {
      const result = await api.importGithubIssues(projectKey, state);
      toast(t('Imported {n} issues ({m} already linked)', { n: result.imported, m: result.alreadyLinked }));
    } catch (e) {
      toast((e as ApiError).message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const ciUrl = `${window.location.origin}/api/integrations/ci/${projectKey}`;
  const curl = `curl -X POST ${ciUrl} \\\n  -H "X-FakeJIRA-Token: ${secret ?? '<connect Git hosting above to get a secret>'}" -H "Content-Type: application/json" \\\n  -d '{"name":"tests","status":"success","ref":"'"$BRANCH"'","url":"'"$BUILD_URL"'"}'`;

  if (settings === undefined) return null;
  return (
    <section className="panel" id="github-repo">
      <h2 className="panel-title"><GitFork size={16} aria-hidden /> {t('GitHub repository')}</h2>
      <p className="muted small hint">
        {t('Create branches and draft pull requests from tasks, and keep GitHub issues in sync. Use a fine-grained access token with read and write access to contents, pull requests and issues.')}
      </p>
      <form className="form" onSubmit={save}>
        {error && <div className="alert" role="alert">{error}</div>}
        <div className="form-grid two">
          <label className="field">
            <span>{t('Repository')}</span>
            <input value={repo} placeholder="acme/website" onChange={(e) => setRepo(e.target.value)} />
          </label>
          <label className="field">
            <span>{t('Access token')} {settings?.hasToken && <span className="muted small">({t('saved; leave empty to keep it')})</span>}</span>
            <input type="password" value={token} autoComplete="off" placeholder="github_pat_…" onChange={(e) => setToken(e.target.value)} />
          </label>
        </div>
        <label className="toggle">
          <input type="checkbox" checked={issueSync} onChange={(e) => setIssueSync(e.target.checked)} />
          {t('Sync issues both ways (also subscribe the webhook to “Issues” events)')}
        </label>
        <div className="button-row">
          <button className="btn btn-soft" disabled={busy || !repo.trim()}>{settings ? t('Save') : t('Connect')}</button>
          {settings && (
            <button type="button" className="btn btn-ghost danger" disabled={busy} onClick={async () => {
              await api.removeGithubRepo(projectKey);
              setSettings(null);
              setRepo('');
              toast(t('Repository disconnected'));
            }}>{t('Disconnect')}</button>
          )}
        </div>
      </form>
      {settings?.hasToken && (
        <div className="button-row">
          <button className="btn btn-ghost" disabled={busy} onClick={() => importIssues('open')}>{t('Import open issues')}</button>
          <button className="btn btn-ghost" disabled={busy} onClick={() => importIssues('all')}>{t('Import all issues')}</button>
        </div>
      )}

      <h3 className="subheading"><Hammer size={15} aria-hidden /> {t('CI/CD status')}</h3>
      <p className="muted small">
        {t('GitHub Actions, checks and commit statuses, and GitLab pipelines report automatically through the webhook above (add the “Check runs”, “Workflow runs” or “Pipeline” events). Any other CI can post results like this:')}
      </p>
      <pre className="code-block">{curl}</pre>
    </section>
  );
}
