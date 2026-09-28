const HUES = [250, 200, 160, 20, 330, 280, 45, 185];

export function Avatar({ name, size = 28 }: { name: string; size?: number }) {
  const hue = HUES[[...name].reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % HUES.length];
  return (
    <span
      className="avatar"
      title={name}
      style={{
        width: size,
        height: size,
        fontSize: size * 0.42,
        background: `hsl(${hue} 70% 45%)`,
      }}
    >
      {name.slice(0, 2).toUpperCase()}
    </span>
  );
}
