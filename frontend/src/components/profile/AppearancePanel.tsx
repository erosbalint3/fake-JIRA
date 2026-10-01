import { Palette } from 'lucide-react';
import { setAppearance, useAppearance, type Appearance } from '../../theme';
import { t } from '../../i18n';

type Option<K extends keyof Appearance> = [Appearance[K], string];

function Choice<K extends keyof Appearance>({ name, label, field, options }: {
  name: string; label: string; field: K; options: Option<K>[];
}) {
  const a = useAppearance();
  return (
    <fieldset className="appearance-choice">
      <legend>{label}</legend>
      <div className="segmented" role="radiogroup" aria-label={label}>
        {options.map(([value, text]) => (
          <label key={String(value)} className={a[field] === value ? 'active' : ''}>
            <input type="radio" name={name} checked={a[field] === value}
              onChange={() => setAppearance({ ...a, [field]: value })} />
            {text}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/** Theme, contrast, density, font and text size; follows the person to every device. */
export function AppearancePanel() {
  const a = useAppearance();
  return (
    <section className="panel">
      <h2 className="panel-title"><Palette size={16} /> {t('Appearance')}</h2>
      <div className="appearance-grid">
        <Choice name="theme" label={t('Theme')} field="theme"
          options={[['system', t('Match system')], ['light', t('Light')], ['dark', t('Dark')]]} />
        <Choice name="contrast" label={t('Contrast')} field="contrast" options={[['normal', t('Standard')], ['high', t('High')]]} />
        <Choice name="density" label={t('Density')} field="density"
          options={[['comfortable', t('Comfortable')], ['compact', t('Compact')]]} />
        <Choice name="textSize" label={t('Text size')} field="textSize"
          options={[['small', t('Small')], ['normal', t('Normal')], ['large', t('Large')], ['larger', t('Larger')]]} />
        <Choice name="font" label={t('Font')} field="font"
          options={[['default', t('Default')], ['system', t('System')], ['readable', t('Easy to read')], ['serif', t('Serif')], ['mono', t('Monospace')]]} />
      </div>
      <label className="toggle">
        <input type="checkbox" checked={a.reduceMotion} onChange={(e) => setAppearance({ ...a, reduceMotion: e.target.checked })} />
        {t('Reduce motion')}
      </label>
    </section>
  );
}
