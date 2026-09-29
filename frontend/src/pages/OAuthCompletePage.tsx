import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Clock } from 'lucide-react';
import { ApiError } from '../api';
import { useAuth } from '../auth';
import { Logo } from '../components/Logo';
import { Spinner } from '../components/States';

/** Google/GitHub sign-in lands here with the outcome in the URL fragment (never sent to servers). */
export function OAuthCompletePage() {
  const { acceptToken, user } = useAuth();
  const navigate = useNavigate();
  const [message, setMessage] = useState<{ title: string; text: string; pending?: boolean } | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.hash.slice(1));
    // Drop the token from the address bar and history.
    window.history.replaceState(null, '', '/oauth-complete');
    const token = params.get('token');
    const challenge = params.get('challenge');
    const linked = params.get('linked');
    if (token) {
      acceptToken(token).then(() => navigate('/', { replace: true }))
        .catch((e: ApiError) => setMessage({ title: 'Sign-in failed', text: e.message }));
    } else if (challenge) {
      navigate('/login', { replace: true, state: { challenge } });
    } else if (linked) {
      navigate('/profile', { replace: true, state: { linked } });
    } else if (params.get('pending')) {
      setMessage({ title: 'Almost there', text: 'Your account was created and is waiting for an administrator to approve it.', pending: true });
    } else {
      setMessage({ title: 'Sign-in failed', text: params.get('error') ?? 'Something went wrong. Please try again.' });
    }
    // Runs once for the fragment we arrived with.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!message) return <div className="fullscreen"><Spinner label="Signing you in…" /></div>;
  return (
    <div className="auth-simple">
      <div className="auth-simple-logo"><Logo /></div>
      <div className="auth-card form">
        {message.pending && <Clock size={32} className="auth-icon" />}
        <h2>{message.title}</h2>
        <p className={message.pending ? 'muted' : 'alert'}>{message.text}</p>
        <Link to={user ? '/profile' : '/login'} className="btn btn-primary btn-block">
          {user ? 'Back to your profile' : 'Back to sign in'}
        </Link>
      </div>
    </div>
  );
}
