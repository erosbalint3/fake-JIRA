import { useEffect, useMemo, useRef, useState } from 'react';
import type { VelocityEntry } from '../types';
import { t } from '../i18n';

const HEIGHT = 260;
const PAD = { top: 16, right: 16, bottom: 36, left: 40 };
const BAR_MAX = 24;
const BAR_GAP = 2;

/**
 * Velocity: story points committed vs. completed per finished sprint, as grouped bars.
 * Colors come from the categorical chart palette (slot 0 completed, slot 1 committed).
 */
export function VelocityChart({ entries }: { entries: VelocityEntry[] }) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  const [hover, setHover] = useState<number | null>(null);
  const [asTable, setAsTable] = useState(false);

  useEffect(() => {
    const element = wrapRef.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(280, entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, [asTable]);

  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const yMax = Math.max(1, ...entries.map((e) => Math.max(e.committedPoints, e.completedPoints)));
  const band = plotW / Math.max(1, entries.length);
  const bar = Math.max(4, Math.min(BAR_MAX, (band * 0.6 - BAR_GAP) / 2));
  const y = (v: number) => PAD.top + plotH - (v / yMax) * plotH;

  const yTicks = useMemo(() => {
    const step = Math.max(1, Math.ceil(yMax / 5));
    const ticks: number[] = [];
    for (let v = 0; v <= yMax; v += step) ticks.push(v);
    if (ticks[ticks.length - 1] !== yMax) ticks.push(yMax);
    return ticks;
  }, [yMax]);

  // Rounded top corners only: a path with 4px radius on the upper edge.
  const barPath = (x0: number, value: number) => {
    const top = y(value);
    const bottom = PAD.top + plotH;
    const h = bottom - top;
    if (h <= 0) return '';
    const r = Math.min(4, h, bar / 2);
    return `M${x0},${bottom} V${top + r} Q${x0},${top} ${x0 + r},${top} H${x0 + bar - r} Q${x0 + bar},${top} ${x0 + bar},${top + r} V${bottom} Z`;
  };

  const hovered = hover === null ? null : entries[hover];

  return (
    <div className="viz-root">
      <div className="viz-head">
        <ul className="viz-legend" aria-label={t('Legend')}>
          <li><i className="swatch-box" style={{ background: 'var(--cat-1)' }} />{t('Committed')}</li>
          <li><i className="swatch-box" style={{ background: 'var(--cat-0)' }} />{t('Completed')}</li>
        </ul>
        <button type="button" className="link small" onClick={() => setAsTable(!asTable)}>
          {asTable ? t('Show chart') : t('Show as table')}
        </button>
      </div>

      {asTable ? (
        <div className="viz-table-wrap">
          <table className="viz-table">
            <thead><tr><th>{t('Sprint')}</th><th>{t('Committed pts')}</th><th>{t('Completed pts')}</th><th>{t('Tasks done')}</th></tr></thead>
            <tbody>
              {entries.map((e) => (
                <tr key={e.sprintId}>
                  <td>{e.name}</td>
                  <td>{e.committedPoints}</td>
                  <td>{e.completedPoints}</td>
                  <td>{e.completedTasks} / {e.committedTasks}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="viz-plot" ref={wrapRef}>
          <svg width={width} height={HEIGHT} role="img"
            aria-label={t('Velocity over the last {n} sprints', { n: entries.length })}>
            {yTicks.map((v) => (
              <g key={v}>
                <line x1={PAD.left} x2={PAD.left + plotW} y1={y(v)} y2={y(v)} className="viz-grid" />
                <text x={PAD.left - 8} y={y(v)} dy="0.32em" textAnchor="end" className="viz-axis">{v}</text>
              </g>
            ))}
            {entries.map((e, i) => {
              const center = PAD.left + band * i + band / 2;
              const x0 = center - bar - BAR_GAP / 2;
              const label = e.name.length > 14 && band < 110 ? `${e.name.slice(0, 12)}…` : e.name;
              return (
                <g key={e.sprintId} onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)}>
                  <rect x={PAD.left + band * i} y={PAD.top} width={band} height={plotH} fill="transparent" />
                  {hover === i && <rect x={PAD.left + band * i + 2} y={PAD.top} width={band - 4} height={plotH} className="viz-band" />}
                  <path d={barPath(x0, e.committedPoints)} style={{ fill: 'var(--cat-1)' }} />
                  <path d={barPath(x0 + bar + BAR_GAP, e.completedPoints)} style={{ fill: 'var(--cat-0)' }} />
                  <text x={center} y={HEIGHT - 14} textAnchor="middle" className="viz-axis">{label}</text>
                </g>
              );
            })}
          </svg>
          {hovered && hover !== null && (
            <div className="viz-tooltip" style={{
              left: Math.min(PAD.left + band * hover + band / 2 + 16, width - 190),
              top: PAD.top,
            }}>
              <strong>{hovered.name}</strong>
              <span><i className="swatch-box" style={{ background: 'var(--cat-1)' }} />{t('{n} pts committed', { n: hovered.committedPoints })}</span>
              <span><i className="swatch-box" style={{ background: 'var(--cat-0)' }} />{t('{n} pts completed', { n: hovered.completedPoints })}</span>
              <span>{t('{done} of {total} tasks done', { done: hovered.completedTasks, total: hovered.committedTasks })}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
