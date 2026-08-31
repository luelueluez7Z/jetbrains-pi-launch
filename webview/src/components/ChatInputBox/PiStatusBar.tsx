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

/** provider-balance 扩展的余额段识别；不要包含 $，避免误匹配会话费用。 */
const BALANCE_RE = /💰|余额|额度|¥|元/;
/** pi 会话费用段（formatPiStatus 当前输出为 "$0.142"）。 */
const COST_RE = /^\s*\$\s*\d+(?:\.\d+)?\s*$/;

/** 统计段细分：context（上下文占用）/ cache / tokens —— 仅颜色区分，无 emoji */
interface StatsPart {
  text: string;
  cls?: string;
}

function classifyStats(part: string): StatsPart {
  if (COST_RE.test(part)) return { text: part, cls: 'pi-status-cost' };
  if (BALANCE_RE.test(part)) return { text: part.replace(/💰/g, '').trim(), cls: 'pi-status-balance' };
  if (/cache/i.test(part)) return { text: part, cls: 'pi-status-cache' };
  if (/[↑↓]/.test(part)) return { text: part, cls: 'pi-status-tokens' };
  // 上下文占用：197.5K/400.0K (49%)、12.3K/200K (6%)、0/200.0K (0%) —— 数字可选小数，可带 K/k 单位
  if (/\d+(?:\.\d+)?[Kk]?\s*\/\s*\d+(?:\.\d+)?[Kk]?/.test(part)) return { text: part, cls: 'pi-status-context' };
  if (/^(?:plan|计划)\b/i.test(part.trim())) return { text: part, cls: 'pi-status-plan' };
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
  // 保持后端状态字符串中每个 "·" 分隔段的原始顺序，逐段着色。
  const stats = rest.map(classifyStats);

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
    </div>
  );
}
