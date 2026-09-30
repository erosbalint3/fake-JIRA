import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { formatDay } from '../format';
import type { CycleTask, FlowDay, Throughput } from '../types';

export const HEIGHT = 260;
export const PAD = { top: 14, right: 16, bottom: 30, left: 40 };

export function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(280, entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return { ref, width };
}

function ticks(max: number) {
  const step = Math.max(1, Math.ceil(max / 5));
  const list: number[] = [];
  for (let v = 0; v <= max; v += step) list.push(v);
  return list;
}

export function Frame({ width, max, children, label, xLabels }: {
  width: number; max: number; children: ReactNode; label: string; xLabels: { x: number; text: string }[];
}) {
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const y = (v: number) => PAD.top + plotH - (v / Math.max(1, max)) * plotH;
  return (
    <svg width={width} height={HEIGHT} role="img" aria-label={label}>
      {ticks(max).map((v) => (
        <g key={v}>
          <line x1={PAD.left} x2={PAD.left + plotW} y1={y(v)} y2={y(v)} className="viz-grid" />
          <text x={PAD.left - 8} y={y(v)} dy="0.32em" textAnchor="end" className="viz-axis">{v}</text>
        </g>
      ))}
      {xLabels.map((l) => <text key={l.x} x={l.x} y={HEIGHT - 10} textAnchor="middle" className="viz-axis">{l.text}</text>)}
      {children}
    </svg>
  );
}

const BANDS: { key: keyof Omit<FlowDay, 'date'>; label: string; className: string }[] = [
  { key: 'done', label: 'Done', className: 'band-done' },
  { key: 'inReview', label: 'In review', className: 'band-review' },
  { key: 'inProgress', label: 'In progress', className: 'band-progress' },
  { key: 'todo', label: 'To do', className: 'band-todo' },
];

/** Cumulative flow: stacked task counts per status per day. Widening middle bands mean work piles up. */
export function CumulativeFlowChart({ days }: { days: FlowDay[] }) {
  const { ref, width } = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const max = Math.max(1, ...days.map((d) => d.todo + d.inProgress + d.inReview + d.done));
  const x = (i: number) => PAD.left + (days.length <= 1 ? plotW / 2 : (i / (days.length - 1)) * plotW);
  const y = (v: number) => PAD.top + plotH - (v / max) * plotH;
  const every = Math.max(1, Math.ceil(days.length / Math.max(2, Math.floor(plotW / 70))));

  // Bands stack bottom-up in BANDS order.
  const paths = BANDS.map((band, b) => {
    const below = (d: FlowDay) => BANDS.slice(0, b).reduce((s, x2) => s + d[x2.key], 0);
    const top = days.map((d, i) => `${i ? 'L' : 'M'}${x(i)},${y(below(d) + d[band.key])}`).join(' ');
    const bottom = days.map((d, i) => ({ d, i })).reverse().map(({ d, i }) => `L${x(i)},${y(below(d))}`).join(' ');
    return { band, d: `${top} ${bottom} Z` };
  });
  const hovered = hover === null ? null : days[hover];

  return (
    <div className="viz-root">
      <ul className="viz-legend" aria-label="Legend">
        {[...BANDS].reverse().map((b) => <li key={b.key}><i className={`swatch ${b.className}`} />{b.label}</li>)}
      </ul>
      <div className="viz-plot" ref={ref}>
        <Frame width={width} max={max} label="Cumulative flow diagram"
          xLabels={days.map((d, i) => ({ x: x(i), text: formatDay(d.date), i })).filter((l) => l.i % every === 0)}>
          {paths.map(({ band, d }) => <path key={band.key} d={d} className={`cfd-band ${band.className}`} />)}
          {hover !== null && <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotH} className="viz-crosshair" />}
          <rect x={PAD.left} y={PAD.top} width={plotW} height={plotH} fill="transparent"
            onPointerMove={(e) => {
              const rect = e.currentTarget.getBoundingClientRect();
              const index = Math.round(((e.clientX - rect.left) / rect.width) * (days.length - 1));
              setHover(Math.max(0, Math.min(days.length - 1, index)));
            }}
            onPointerLeave={() => setHover(null)} />
        </Frame>
        {hovered && hover !== null && (
          <div className="viz-tooltip" style={{ left: Math.min(x(hover) + 12, width - 170), top: PAD.top }}>
            <strong>{formatDay(hovered.date, true)}</strong>
            {[...BANDS].reverse().map((b) => <span key={b.key}><i className={`swatch ${b.className}`} />{hovered[b.key]} {b.label.toLowerCase()}</span>)}
          </div>
        )}
      </div>
    </div>
  );
}

/** Each finished task as a dot: when it finished (x) and how long it took (y), with the 85th percentile line. */
export function CycleScatter({ tasks, p85, measure }: { tasks: CycleTask[]; p85: number | null; measure: 'cycle' | 'lead' }) {
  const { ref, width } = useWidth();
  const [hover, setHover] = useState<CycleTask | null>(null);
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const value = (t: CycleTask) => (measure === 'cycle' ? t.cycleDays : t.leadDays);
  const shown = tasks.filter((t) => value(t) !== null);
  const max = Math.max(1, Math.ceil(Math.max(...shown.map((t) => value(t)!), p85 ?? 0)));
  const times = shown.map((t) => new Date(t.completedAt).getTime());
  const minT = Math.min(...times);
  const maxT = Math.max(...times);
  const x = (t: CycleTask) => PAD.left + (maxT === minT ? plotW / 2 : ((new Date(t.completedAt).getTime() - minT) / (maxT - minT)) * plotW);
  const y = (v: number) => PAD.top + plotH - (v / max) * plotH;
  const labels = shown.length ? [
    { x: PAD.left, text: formatDay(shown[0].completedAt.slice(0, 10)) },
    { x: PAD.left + plotW, text: formatDay(shown[shown.length - 1].completedAt.slice(0, 10)) },
  ] : [];
  return (
    <div className="viz-plot" ref={ref}>
      <Frame width={width} max={max} label={`${measure === 'cycle' ? 'Cycle' : 'Lead'} time per task in days`} xLabels={labels}>
        {p85 !== null && (
          <g>
            <line x1={PAD.left} x2={PAD.left + plotW} y1={y(p85)} y2={y(p85)} className="viz-p85" />
            <text x={PAD.left + plotW} y={y(p85) - 5} textAnchor="end" className="viz-axis">85% within {p85} d</text>
          </g>
        )}
        {shown.map((t) => (
          <Link key={t.id} to={`/tasks/${t.id}`} aria-label={`${t.key}: ${value(t)} days`}>
            <circle cx={x(t)} cy={y(value(t)!)} r={hover?.id === t.id ? 6 : 4.5} className="viz-dot-cycle"
              onPointerEnter={() => setHover(t)} onPointerLeave={() => setHover(null)} />
          </Link>
        ))}
      </Frame>
      {hover && (
        <div className="viz-tooltip" style={{ left: Math.min(x(hover) + 12, width - 190), top: PAD.top }}>
          <strong>{hover.key}</strong>
          <span>{hover.title}</span>
          <span>{value(hover)} days · finished {formatDay(hover.completedAt.slice(0, 10))}</span>
        </div>
      )}
    </div>
  );
}

/** Tasks (bars) finished per week. */
export function ThroughputChart({ weeks, unit }: { weeks: Throughput[]; unit: 'tasks' | 'points' }) {
  const { ref, width } = useWidth();
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const value = (w: Throughput) => (unit === 'points' ? w.points : w.tasks);
  const max = Math.max(1, ...weeks.map(value));
  const slot = plotW / Math.max(1, weeks.length);
  const barW = Math.max(6, Math.min(36, slot * 0.6));
  const y = (v: number) => PAD.top + plotH - (v / max) * plotH;
  const every = Math.max(1, Math.ceil(weeks.length / Math.max(2, Math.floor(plotW / 70))));
  return (
    <div className="viz-plot" ref={ref}>
      <Frame width={width} max={max} label={`${unit === 'points' ? 'Points' : 'Tasks'} finished per week`}
        xLabels={weeks.map((w, i) => ({ x: PAD.left + slot * (i + 0.5), text: formatDay(w.weekStart), i })).filter((l) => l.i % every === 0)}>
        {weeks.map((w, i) => (
          <g key={w.weekStart}>
            <rect x={PAD.left + slot * (i + 0.5) - barW / 2} y={y(value(w))} width={barW} height={PAD.top + plotH - y(value(w))}
              rx={3} className="viz-bar-completed"><title>{`Week of ${w.weekStart}: ${value(w)} ${unit}`}</title></rect>
            {value(w) > 0 && <text x={PAD.left + slot * (i + 0.5)} y={y(value(w)) - 5} textAnchor="middle" className="viz-axis">{value(w)}</text>}
          </g>
        ))}
      </Frame>
    </div>
  );
}
