import { useState } from 'react';
import { Frame, HEIGHT, PAD, useWidth } from './FlowCharts';

/** Chart colours, in order; each has a light and dark variant in the stylesheet. */
const SERIES = ['series-1', 'series-2', 'series-3', 'series-4', 'series-5', 'series-6', 'series-7', 'series-8'];

export interface Series {
  label: string;
  values: number[];
  /** Dashed line (targets, projections). */
  dashed?: boolean;
}

function Legend({ labels, dashed = [] }: { labels: string[]; dashed?: boolean[] }) {
  return (
    <ul className="viz-legend">
      {labels.map((label, i) => (
        <li key={label}><span className={`series-swatch ${SERIES[i % SERIES.length]} ${dashed[i] ? 'dashed' : ''}`} aria-hidden />{label}</li>
      ))}
    </ul>
  );
}

/** Lines over a shared x axis, with a hover read-out. */
export function LineChart({ series, xLabels, label, max: fixedMax }: {
  series: Series[]; xLabels: string[]; label: string; max?: number;
}) {
  const { ref, width } = useWidth();
  const [hover, setHover] = useState<number | null>(null);
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const max = fixedMax ?? Math.max(1, ...series.flatMap((s) => s.values));
  const n = Math.max(1, xLabels.length - 1);
  const x = (i: number) => PAD.left + (i / n) * plotW;
  const y = (v: number) => PAD.top + plotH - (v / max) * plotH;
  const every = Math.max(1, Math.ceil(xLabels.length / Math.max(2, Math.floor(plotW / 70))));
  return (
    <div className="viz-root">
      <Legend labels={series.map((s) => s.label)} dashed={series.map((s) => !!s.dashed)} />
      <div className="viz-plot" ref={ref}
        onMouseMove={(e) => {
          const box = e.currentTarget.getBoundingClientRect();
          const i = Math.round(((e.clientX - box.left - PAD.left) / plotW) * n);
          setHover(i >= 0 && i < xLabels.length ? i : null);
        }}
        onMouseLeave={() => setHover(null)}>
        <Frame width={width} max={max} label={label}
          xLabels={xLabels.map((text, i) => ({ x: x(i), text, i })).filter((l) => l.i % every === 0 || l.i === xLabels.length - 1)}>
          {series.map((s, si) => (
            <polyline key={s.label} className={`viz-series ${SERIES[si % SERIES.length]} ${s.dashed ? 'dashed' : ''}`}
              points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(' ')} />
          ))}
          {hover !== null && (
            <>
              <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotH} className="viz-crosshair" />
              {series.map((s, si) => s.values[hover] !== undefined && (
                <circle key={s.label} cx={x(hover)} cy={y(s.values[hover])} r={4} className={`viz-dot ${SERIES[si % SERIES.length]}`} />
              ))}
            </>
          )}
        </Frame>
        {hover !== null && (
          <div className="viz-tooltip" style={{ left: Math.min(x(hover) + 12, width - 190), top: PAD.top }}>
            <strong>{xLabels[hover]}</strong>
            {series.map((s, si) => (
              <span key={s.label}><span className={`series-swatch ${SERIES[si % SERIES.length]}`} aria-hidden />{s.label}: {s.values[hover]}</span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/** Bars per category; several series are drawn side by side. */
export function BarChart({ series, xLabels, label }: { series: Series[]; xLabels: string[]; label: string }) {
  const { ref, width } = useWidth();
  const plotW = width - PAD.left - PAD.right;
  const plotH = HEIGHT - PAD.top - PAD.bottom;
  const max = Math.max(1, ...series.flatMap((s) => s.values));
  const slot = plotW / Math.max(1, xLabels.length);
  const groupW = Math.min(64, slot * 0.7);
  const barW = Math.max(3, groupW / Math.max(1, series.length));
  const y = (v: number) => PAD.top + plotH - (v / max) * plotH;
  const every = Math.max(1, Math.ceil(xLabels.length / Math.max(2, Math.floor(plotW / 70))));
  return (
    <div className="viz-root">
      {series.length > 1 && <Legend labels={series.map((s) => s.label)} />}
      <div className="viz-plot" ref={ref}>
        <Frame width={width} max={max} label={label}
          xLabels={xLabels.map((text, i) => ({ x: PAD.left + slot * (i + 0.5), text, i })).filter((l) => l.i % every === 0)}>
          {xLabels.map((category, i) => series.map((s, si) => {
            const v = s.values[i] ?? 0;
            const left = PAD.left + slot * (i + 0.5) - groupW / 2 + si * barW;
            return (
              <rect key={`${category}-${s.label}`} x={left} y={y(v)} width={Math.max(2, barW - 2)} height={PAD.top + plotH - y(v)}
                rx={2} className={`viz-fill ${SERIES[si % SERIES.length]}`}>
                <title>{`${category} · ${s.label}: ${v}`}</title>
              </rect>
            );
          }))}
        </Frame>
      </div>
    </div>
  );
}

/** A donut with a legend of values and shares. */
export function DonutChart({ slices, label }: { slices: { label: string; value: number }[]; label: string }) {
  const total = slices.reduce((sum, s) => sum + s.value, 0);
  const radius = 70;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;
  return (
    <div className="donut">
      <svg width={180} height={180} viewBox="0 0 180 180" role="img" aria-label={label}>
        <circle cx={90} cy={90} r={radius} className="donut-track" />
        {total > 0 && slices.map((slice, i) => {
          const length = (slice.value / total) * circumference;
          const element = (
            <circle key={slice.label} cx={90} cy={90} r={radius} className={`donut-slice ${SERIES[i % SERIES.length]}`}
              strokeDasharray={`${length} ${circumference - length}`} strokeDashoffset={-offset} transform="rotate(-90 90 90)">
              <title>{`${slice.label}: ${slice.value}`}</title>
            </circle>
          );
          offset += length;
          return element;
        })}
        <text x={90} y={86} textAnchor="middle" className="donut-total">{total}</text>
        <text x={90} y={106} textAnchor="middle" className="viz-axis">total</text>
      </svg>
      <ul className="donut-legend">
        {slices.map((slice, i) => (
          <li key={slice.label}>
            <span className={`series-swatch ${SERIES[i % SERIES.length]}`} aria-hidden />
            <span className="donut-label">{slice.label}</span>
            <strong>{slice.value}</strong>
            <span className="muted small">{total ? Math.round((slice.value / total) * 100) : 0}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
