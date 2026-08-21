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

/** 余额段识别：💰 / 额度 / 余额 / 货币符号 */
const BALANCE_RE = /💰|余额|额度|¥|\$|元/;

/** 统计段细分：context（上下文占用）/ cache / tokens —— 仅颜色区分，无 emoji */
interface StatsPart {
  text: string;
  cls?: string;
}

function classifyStats(part: string): StatsPart {
  if (/cache/i.test(part)) return { text: part, cls: 'pi-status-cache' };
  if (/[↑↓]/.test(part)) return { text: part, cls: 'pi-status-tokens' };
  // 上下文占用：197.5K/400.0K (49%)、12.3K/200K (6%)、0/200.0K (0%) —— 数字可选小数，可带 K/k 单位
  if (/\d+(?:\.\d+)?[Kk]?\s*\/\s*\d+(?:\.\d+)?[Kk]?/.test(part)) return { text: part, cls: 'pi-status-context' };
  return { text: part };
}

/** 分段渲染状态栏：状态主文本（随状态变色）· 统计（仅颜色分层）· 余额（金色，不加粗） */
export function PiStatusBar({ status }: PiStatusBarProps) {
  const raw = (status || 'ready').replace(/^●\s*/, '');
  const kind = kindOf(raw);
  const Icon =
    kind === 'busy' ? Loader2 : kind === 'disconnected' ? CircleX : kind === 'idle' ? Activity : Wifi;

  const parts = raw.split(' · ').filter(Boolean);
  const [phase, ...rest] = parts;
  const balanceIdx = rest.findIndex((s) => BALANCE_RE.test(s));
  const balance = balanceIdx >= 0 ? rest[balanceIdx].replace(/💰/g, '').trim() : null;
  const stats = (balanceIdx >= 0 ? rest.filter((_, i) => i !== balanceIdx) : rest).map(classifyStats);

  return (
    <div className="pi-status-bar" role="status" aria-live="polite" title={raw}>
      <span className={`pi-status-bar-icon pi-status-${kind}`}>
        <Icon size={16} strokeWidth={2.2} />
      </span>
      <span className={`pi-status-bar-text pi-status-phase pi-status-phase-${kind}`}>
        {phase}
      </span>
      {stats.map((s, i) => (
        <span key={i} className={`pi-status-bar-text pi-status-stats${s.cls ? ` ${s.cls}` : ''}`}>
          {' · '}
          {s.text}
        </span>
      ))}
      {balance && (
        <span className="pi-status-bar-text pi-status-balance"> · {balance}</span>
      )}
    </div>
  );
}
