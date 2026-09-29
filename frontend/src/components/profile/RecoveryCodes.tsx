import { Copy, Download } from 'lucide-react';
import { saveBlob } from '../../api';
import { useToast } from '../../toast';

/** Shows freshly generated recovery codes once, with copy and download. */
export function RecoveryCodes({ codes }: { codes: string[] }) {
  const toast = useToast();
  const text = `FakeJIRA recovery codes (${window.location.host})\nEach code works once.\n\n${codes.join('\n')}\n`;
  return (
    <div className="recovery">
      <p className="small"><b>Save these recovery codes.</b> Each one signs you in once if you lose your phone. They won't be shown again.</p>
      <ol className="recovery-codes mono">
        {codes.map((code) => <li key={code}>{code}</li>)}
      </ol>
      <div className="button-row">
        <button type="button" className="btn btn-ghost btn-sm" onClick={async () => {
          try {
            await navigator.clipboard.writeText(text);
            toast('Recovery codes copied');
          } catch {
            window.prompt('Copy your recovery codes:', codes.join(' '));
          }
        }}><Copy size={14} /> Copy</button>
        <button type="button" className="btn btn-ghost btn-sm"
          onClick={() => saveBlob(new Blob([text], { type: 'text/plain' }), 'fakejira-recovery-codes.txt')}>
          <Download size={14} /> Download
        </button>
      </div>
    </div>
  );
}
