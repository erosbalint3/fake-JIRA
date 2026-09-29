import { useEffect, useState, type FormEvent } from 'react';
import QRCode from 'qrcode';
import { ShieldCheck } from 'lucide-react';
import { api, ApiError } from '../../api';
import { useToast } from '../../toast';
import { Modal } from '../Modal';
import { RecoveryCodes } from './RecoveryCodes';
import type { Profile } from '../../types';

/** Turn authenticator-app codes on or off, and manage recovery codes. */
export function TwoFactorPanel({ profile, onChange }: { profile: Profile; onChange: () => void }) {
  const toast = useToast();
  const [setup, setSetup] = useState<{ secret: string; otpauthUrl: string; qr: string } | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [dialog, setDialog] = useState<'disable' | 'codes' | null>(null);

  const start = async () => {
    try {
      const next = await api.twoFactorSetup();
      const qr = await QRCode.toDataURL(next.otpauthUrl, { margin: 1, width: 200 });
      setSetup({ ...next, qr });
    } catch (e) {
      toast((e as ApiError).message, 'error');
    }
  };

  return (
    <section className="panel">
      <h2 className="panel-title"><ShieldCheck size={16} /> Two-step verification</h2>
      {profile.twoFactorEnabled ? (
        <>
          <p className="muted small hint">
            <span className="status-on">On.</span> Signing in asks for a code from your authenticator app.
            {' '}{profile.recoveryCodesLeft} recovery code{profile.recoveryCodesLeft === 1 ? '' : 's'} left.
          </p>
          <div className="button-row">
            <button className="btn btn-soft" onClick={() => setDialog('codes')}>New recovery codes</button>
            <button className="btn btn-ghost danger" onClick={() => setDialog('disable')}>Turn off</button>
          </div>
        </>
      ) : (
        <>
          <p className="muted small hint">
            Protect your account with a code from an authenticator app (Google Authenticator, Microsoft Authenticator,
            1Password, Bitwarden…) in addition to your password.
          </p>
          <button className="btn btn-soft" onClick={start}>Set up</button>
        </>
      )}
      {codes && <RecoveryCodes codes={codes} />}

      {setup && (
        <SetupDialog setup={setup} onClose={() => setSetup(null)} onEnabled={(recovery) => {
          setSetup(null);
          setCodes(recovery);
          toast('Two-step verification is on');
          onChange();
        }} />
      )}
      {dialog && (
        <ConfirmCodeDialog kind={dialog} needsPassword={profile.passwordSet} onClose={() => setDialog(null)}
          onDone={(recovery) => {
            setDialog(null);
            setCodes(recovery ?? null);
            toast(recovery ? 'New recovery codes created' : 'Two-step verification is off');
            onChange();
          }} />
      )}
    </section>
  );
}

function SetupDialog({ setup, onClose, onEnabled }: {
  setup: { secret: string; qr: string };
  onClose: () => void;
  onEnabled: (codes: string[]) => void;
}) {
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      onEnabled((await api.twoFactorEnable(code.trim())).recoveryCodes);
    } catch (e) {
      const err = e as ApiError;
      setError(err.fieldErrors.code ?? err.message);
      setBusy(false);
    }
  };

  return (
    <Modal title="Set up two-step verification" onClose={onClose}
      footer={<>
        <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button type="submit" form="totp-form" className="btn btn-primary" disabled={busy || code.trim().length < 6}>Turn on</button>
      </>}>
      <form id="totp-form" className="form" onSubmit={submit}>
        <ol className="steps small">
          <li>Scan this QR code with your authenticator app.</li>
          <li>Enter the 6-digit code it shows.</li>
        </ol>
        <div className="qr-wrap">
          <img src={setup.qr} width={200} height={200} alt="QR code for your authenticator app" className="qr" />
          <div className="small muted">
            Can't scan? Enter this key:
            <code className="secret">{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</code>
          </div>
        </div>
        {error && <div className="alert">{error}</div>}
        <label className="field">
          <span>Code</span>
          <input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code"
            placeholder="123456" maxLength={7} autoFocus className="code-input" />
        </label>
      </form>
    </Modal>
  );
}

function ConfirmCodeDialog({ kind, needsPassword, onClose, onDone }: {
  kind: 'disable' | 'codes';
  needsPassword: boolean;
  onClose: () => void;
  onDone: (codes?: string[]) => void;
}) {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      if (kind === 'disable') {
        await api.twoFactorDisable(password, code.trim());
        onDone();
      } else {
        onDone((await api.recoveryCodes(code.trim())).recoveryCodes);
      }
    } catch (e) {
      const err = e as ApiError;
      setError(err.fieldErrors.password ?? err.fieldErrors.code ?? err.message);
      setBusy(false);
    }
  };

  return (
    <Modal title={kind === 'disable' ? 'Turn off two-step verification?' : 'Create new recovery codes?'} onClose={onClose}
      footer={<>
        <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
        <button type="submit" form="confirm-code" className={`btn ${kind === 'disable' ? 'btn-danger' : 'btn-primary'}`}
          disabled={busy || !code.trim()}>{kind === 'disable' ? 'Turn off' : 'Create codes'}</button>
      </>}>
      <form id="confirm-code" className="form" onSubmit={submit}>
        {kind === 'codes' && <p className="muted small">Your old recovery codes stop working.</p>}
        {error && <div className="alert">{error}</div>}
        {kind === 'disable' && needsPassword && (
          <label className="field">
            <span>Password</span>
            <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
        )}
        <label className="field">
          <span>Code from your app (or a recovery code)</span>
          <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" className="code-input" autoFocus />
        </label>
      </form>
    </Modal>
  );
}

export function useLinkedToast(linked: string | undefined) {
  const toast = useToast();
  useEffect(() => {
    if (linked) toast(`${linked === 'github' ? 'GitHub' : 'Google'} account connected`);
  }, [linked, toast]);
}
