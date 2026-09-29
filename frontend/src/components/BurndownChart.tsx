import { useEffect, useMemo, useRef, useState, type PointerEvent } from 'react';
import { formatDay } from '../format';
import type { BurndownPoint, ScopeChange } from '../types';

const HEIGHT = 280;
const PAD = { top: 16, right: 64, bottom: 32, left: 40 };

/**
 * Sprint burndown: remaining open tasks (or story points) per day (solid) against the ideal straight line (dashed).
 * Colors come from the validated chart palette (--viz-remaining / --viz-ideal).
 */
export function BurndownChart({ points, total, unit = 'tasks', changes = [] }: {
  points: BurndownPoint[]; total: number; unit?: 'tasks' | 'points'; changes?: ScopeChange[];
}) {
  const noun = unit === 'points' ? 'points' : 'tasks';
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
  const yMax = Math.max(1, total);
  const x = (i: number) => PAD.left + (points.length <= 1 ? plotW / 2 : (i / (points.length - 1)) * plotW);
  const y = (v: number) => PAD.top + plotH - (v / yMax) * plotH;

  const yTicks = useMemo(() => {
    const step = Math.max(1, Math.ceil(yMax / 5));
    const ticks: number[] = [];
    for (let v = 0; v <= yMax; v += step) ticks.push(v);
    if (ticks[ticks.length - 1] !== yMax) ticks.push(yMax);
    return ticks;
  }, [yMax]);

  const xEvery = Math.max(1, Math.ceil(points.length / Math.max(2, Math.floor(plotW / 70))));
  const series = useMemo(() => (unit === 'points'
    ? points.map((p) => ({ ...p, remaining: p.remainingPoints, ideal: p.idealPoints }))
    : points), [points, unit]);
  const actual = series.map((p, i) => ({ ...p, i })).filter((p) => p.remaining !== null);
  const last = actual[actual.length - 1];
  const actualPath = actual.map((p, k) => `${k ? 'L' : 'M'}${x(p.i)},${y(p.remaining!)}`).join(' ');
  const idealPath = series.map((p, i) => `${i ? 'L' : 'M'}${x(i)},${y(p.ideal)}`).join(' ');

  const onMove = (event: PointerEvent<SVGRectElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const relative = ((event.clientX - rect.left) / rect.width) * plotW;
    const index = points.length <= 1 ? 0 : Math.round((relative / plotW) * (points.length - 1));
    setHover(Math.max(0, Math.min(points.length - 1, index)));
  };

  const hovered = hover === null ? null : series[hover];
  // Scope changes per chart day (index), drawn as markers along the top.
  const changesByDay = useMemo(() => {
    const map = new Map<number, ScopeChange[]>();
    changes.forEach((c) => {
      const index = points.findIndex((p) => p.date === c.date);
      if (index >= 0) map.set(index, [...(map.get(index) ?? []), c]);
    });
    return map;
  }, [changes, points]);
  const hoveredChanges = hover === null ? [] : changesByDay.get(hover) ?? [];

  return (
    <div className="viz-root">
      <div className="viz-head">
        <ul className="viz-legend" aria-label="Legend">
          <li><svg width="22" height="10" aria-hidden><line x1="1" y1="5" x2="21" y2="5" className="viz-line-remaining" /></svg>Remaining {noun}</li>
          <li><svg width="22" height="10" aria-hidden><line x1="1" y1="5" x2="21" y2="5" className="viz-line-ideal" /></svg>Ideal</li>
          {changes.length > 0 && <li><span className="scope-marker-key" aria-hidden>◆</span>Scope change</li>}
        </ul>
        <button type="button" className="link small" onClick={() => setAsTable(!asTable)}>
          {asTable ? 'Show chart' : 'Show as table'}
        </button>
      </div>

      {asTable ? (
        <div className="viz-table-wrap">
          <table className="viz-table">
            <thead><tr><th>Day</th><th>Remaining</th><th>Ideal</th></tr></thead>
            <tbody>
              {series.map((p) => (
                <tr key={p.date}>
                  <td>{formatDay(p.date)}</td>
                  <td>{p.remaining ?? '—'}</td>
                  <td>{p.ideal.toFixed(1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="viz-plot" ref={wrapRef}>
          <svg width={width} height={HEIGHT} role="img"
            aria-label={`Burndown: ${last ? `${last.remaining} of ${total} ${noun} remaining` : 'no data yet'}`}>
            {yTicks.map((v) => (
              <g key={v}>
                <line x1={PAD.left} x2={PAD.left + plotW} y1={y(v)} y2={y(v)} className="viz-grid" />
                <text x={PAD.left - 8} y={y(v)} dy="0.32em" textAnchor="end" className="viz-axis">{v}</text>
              </g>
            ))}
            {points.map((p, i) => (i % xEvery === 0 || i === points.length - 1) && (
              <text key={p.date} x={x(i)} y={HEIGHT - 10} textAnchor="middle" className="viz-axis">{formatDay(p.date)}</text>
            ))}

            <path d={idealPath} className="viz-line-ideal" fill="none" />
            {[...changesByDay.entries()].map(([index, list]) => {
              const added = list.filter((c) => c.added).length;
              const removed = list.length - added;
              return (
                <g key={index} className="scope-marker">
                  <line x1={x(index)} x2={x(index)} y1={PAD.top} y2={PAD.top + plotH} className="viz-scope-line" />
                  <text x={x(index)} y={PAD.top + 2} textAnchor="middle" className={`viz-scope ${added >= removed ? 'added' : 'removed'}`}>
                    {added > 0 ? `+${added}` : ''}{added > 0 && removed > 0 ? ' ' : ''}{removed > 0 ? `−${removed}` : ''}
                  </text>
                </g>
              );
            })}
            {actual.length > 0 && <path d={actualPath} className="viz-line-remaining" fill="none" />}
            {last && (
              <>
                <circle cx={x(last.i)} cy={y(last.remaining!)} r={4} className="viz-dot-remaining" />
                <text x={x(last.i) + 10} y={y(last.remaining!)} dy="0.32em" className="viz-direct-label">
                  {last.remaining} left
                </text>
              </>
            )}

            {hovered && hover !== null && (
              <g pointerEvents="none">
                <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotH} className="viz-crosshair" />
                {hovered.remaining !== null && (
                  <circle cx={x(hover)} cy={y(hovered.remaining)} r={4} className="viz-dot-remaining" />
                )}
                <circle cx={x(hover)} cy={y(hovered.ideal)} r={4} className="viz-dot-ideal" />
              </g>
            )}
            <rect x={PAD.left} y={PAD.top} width={plotW} height={plotH} fill="transparent"
              onPointerMove={onMove} onPointerLeave={() => setHover(null)} />
          </svg>
          {hovered && hover !== null && (
            <div className="viz-tooltip" style={{
              left: Math.min(x(hover) + 12, width - 170),
              top: PAD.top,
            }}>
              <strong>{formatDay(hovered.date, true)}</strong>
              <span><i className="swatch remaining" />{hovered.remaining ?? '—'} remaining</span>
              <span><i className="swatch ideal" />{hovered.ideal.toFixed(1)} ideal</span>
              {hoveredChanges.map((c, i) => (
                <span key={i} className="small">{c.added ? '+ added' : '− removed'} {c.key}{c.points !== null ? ` (${c.points} pt)` : ''}</span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
