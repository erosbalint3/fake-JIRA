import { useState } from 'react';
import type { User } from '../types';

const HUES = [250, 200, 160, 20, 330, 280, 45, 185];

interface Props {
  /** Preferred: shows the profile picture when there is one. */
  user?: Pick<User, 'username' | 'displayName' | 'avatarUrl'> | null;
  /** Fallback when only a name is known. */
  name?: string;
  size?: number;
}

export function Avatar({ user, name, size = 28 }: Props) {
  const [broken, setBroken] = useState(false);
  const label = user?.displayName || user?.username || name || '?';
  const seed = user?.username ?? label;
  const hue = HUES[[...seed].reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % HUES.length];
  const style = { width: size, height: size, fontSize: size * 0.42 };

  if (user?.avatarUrl && !broken) {
    return (
      <img className="avatar avatar-img" src={user.avatarUrl} alt="" title={label} style={style}
        onError={() => setBroken(true)} />
    );
  }
  const initials = label.trim().split(/\s+/).length > 1
    ? label.trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join('')
    : label.slice(0, 2);
  return (
    <span className="avatar" title={label} style={{ ...style, background: `hsl(${hue} 65% 32%)` }}>
      {initials.toUpperCase()}
    </span>
  );
}
