import { Activity, CircleX, Loader2, Wifi } from 'lucide-react';

interface PiStatusBarProps {
  status?: string;
}

type StatusKind = 'busy' | 'idle' | 'disconnected' | 'ready';

function kindOf(text: string): StatusKind {
  if (/working|连接中|队列|正在回复|回复中|思考/.test(text)) return 'busy';
  if (/未连接|已断开/.test(text)) return 'disconnected';
  if (/^idle$|已连接|空闲|ready/.test(text)) return 'idle';
  return 'ready';
}

/** Pi/TUI-style telemetry line: state icon + phase text, kept above the composer. */
export function PiStatusBar({ status }: PiStatusBarProps) {
  const text = (status || 'ready').replace(/^●\s*/, '');
  const kind = kindOf(text);
  const Icon =
    kind === 'busy' ? Loader2 : kind === 'disconnected' ? CircleX : kind === 'idle' ? Activity : Wifi;
  return (
    <div className="pi-status-bar" role="status" aria-live="polite" title={text}>
      <span className={`pi-status-bar-icon pi-status-${kind}`}>
        <Icon size={14} strokeWidth={2.2} />
      </span>
      <span className="pi-status-bar-text">{text}</span>
    </div>
  );
}
