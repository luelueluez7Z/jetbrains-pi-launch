import { useEffect, useState } from 'react';
import {
  readSendBehaviorMode,
  writeSendBehaviorMode,
  type SendBehaviorMode,
} from '../../utils/sendBehavior';
import { sendBridgeEvent } from '../../utils/bridge';

interface OptimizeSettingsState {
  model: string;     // 当前配置的优化模型（provider/modelId），空=跟随当前会话模型
  thinking: string;  // 当前配置的推理强度，空=跟随当前会话思考级别
  models: { key: string; label: string }[];  // 可选模型列表
  thinkingLevels: string[];  // 可用推理强度
}

interface SettingsViewProps {
  onClose: () => void;
  initialTab?: string;
  /** 流式发送行为变更时通知 App（同步当前会话的键盘行为） */
  onSendBehaviorModeChange?: (mode: SendBehaviorMode) => void;
}

const THEME_OPTIONS = [
  { value: 'system', label: '跟随 IDE' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
];

const FONT_LEVELS = [
  { level: 1, label: '80%' },
  { level: 2, label: '90%（默认）' },
  { level: 3, label: '100%' },
  { level: 4, label: '110%' },
  { level: 5, label: '120%' },
  { level: 6, label: '140%' },
];

const FONT_SCALE: Record<number, number> = { 1: 0.8, 2: 0.9, 3: 1.0, 4: 1.1, 5: 1.2, 6: 1.4 };

/** 流式发送键位选项（模型对话进行中时回车/Tab 的语义） */
const SEND_BEHAVIOR_OPTIONS: { value: SendBehaviorMode; label: string; hint: string }[] = [
  { value: 'steerOnEnter', label: '回车引导 / Tab 后续', hint: '回车在当前工具回合边界注入引导，Tab 排队等待当前对话完成（默认）' },
  { value: 'followUpOnEnter', label: '回车后续 / Tab 引导', hint: '回车排队等待当前对话完成，Tab 在当前工具回合边界注入引导' },
];

function getInitialTheme(): string {
  const saved = localStorage.getItem('theme');
  return saved === 'light' || saved === 'dark' ? saved : 'system';
}

function applyTheme(pref: string) {
  if (pref === 'system') {
    const ide = (window as unknown as { __INITIAL_IDE_THEME__?: string }).__INITIAL_IDE_THEME__;
    document.documentElement.setAttribute('data-theme', ide === 'light' ? 'light' : 'dark');
  } else {
    document.documentElement.setAttribute('data-theme', pref);
  }
}

function getInitialFontLevel(): number {
  const l = parseInt(localStorage.getItem('fontSizeLevel') || '2', 10);
  return l >= 1 && l <= 6 ? l : 2;
}

function applyFontLevel(level: number) {
  document.documentElement.style.setProperty('--font-scale', String(FONT_SCALE[level] ?? 0.9));
}

/** 极简设置页：主题、字体大小、流式发送行为。 */
const SettingsView = ({ onClose, onSendBehaviorModeChange }: SettingsViewProps) => {
  const [theme, setTheme] = useState(getInitialTheme);
  const [fontLevel, setFontLevel] = useState(getInitialFontLevel);
  const [sendBehaviorMode, setSendBehaviorMode] = useState<SendBehaviorMode>(readSendBehaviorMode);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    applyFontLevel(fontLevel);
  }, [fontLevel]);

  const handleThemeChange = (value: string) => {
    setTheme(value);
    localStorage.setItem('theme', value);
  };

  const handleFontChange = (level: number) => {
    setFontLevel(level);
    localStorage.setItem('fontSizeLevel', String(level));
  };

  const handleSendBehaviorChange = (mode: SendBehaviorMode) => {
    setSendBehaviorMode(mode);
    writeSendBehaviorMode(mode);
    onSendBehaviorModeChange?.(mode);
  };

  // 提示词优化配置：从后端读（editor-prompt-optimize.json），保存写回后端
  const [optimize, setOptimize] = useState<OptimizeSettingsState>({
    model: '',
    thinking: '',
    models: [],
    thinkingLevels: ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
  });

  useEffect(() => {
    sendBridgeEvent('get_optimize_settings');
    window.updateOptimizeSettings = (json: string) => {
      try {
        const data = JSON.parse(json) as Partial<OptimizeSettingsState>;
        setOptimize(prev => ({
          model: typeof data.model === 'string' ? data.model : prev.model,
          thinking: typeof data.thinking === 'string' ? data.thinking : prev.thinking,
          models: Array.isArray(data.models) && data.models.length > 0 ? data.models : prev.models,
          thinkingLevels:
            Array.isArray(data.thinkingLevels) && data.thinkingLevels.length > 0
              ? data.thinkingLevels
              : prev.thinkingLevels,
        }));
      } catch {
        // 忽略解析失败
      }
    };
    return () => {
      delete window.updateOptimizeSettings;
    };
  }, []);

  const handleOptimizeModelChange = (key: string) => {
    setOptimize(prev => ({ ...prev, model: key }));
    sendBridgeEvent('set_optimize_settings', JSON.stringify({ model: key, thinking: optimize.thinking }));
  };

  const handleOptimizeThinkingChange = (level: string) => {
    setOptimize(prev => ({ ...prev, thinking: level }));
    sendBridgeEvent('set_optimize_settings', JSON.stringify({ model: optimize.model, thinking: level }));
  };

  const selectedOptimizeLabel =
    optimize.models.find((m) => m.key === optimize.model)?.label
    ?? (optimize.model ? optimize.model : '跟随当前会话模型');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--bg-chat, #1e1e1e)' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '10px 14px',
          borderBottom: '1px solid var(--border-secondary, rgba(127,127,127,0.25))',
          flexShrink: 0,
        }}
      >
        <button
          className="icon-button"
          onClick={onClose}
          title="返回"
          style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 16, color: 'var(--text-secondary)' }}
        >
          ←
        </button>
        <span style={{ fontSize: 14, fontWeight: 600 }}>设置</span>
      </div>

      <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 24, overflowY: 'auto' }}>
        {/* 主题 */}
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>主题</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {THEME_OPTIONS.map((o) => (
              <button
                key={o.value}
                onClick={() => handleThemeChange(o.value)}
                style={{
                  padding: '6px 14px',
                  borderRadius: 6,
                  border: `1px solid ${theme === o.value ? 'var(--accent-primary, #4b8bf5)' : 'var(--border-secondary, rgba(127,127,127,0.35))'}`,
                  background: theme === o.value ? 'color-mix(in srgb, var(--accent-primary, #4b8bf5) 18%, transparent)' : 'transparent',
                  color: 'var(--text-primary)',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                {o.label}
              </button>
            ))}
          </div>
        </div>

        {/* 字体大小 */}
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>字体大小</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {FONT_LEVELS.map((f) => (
              <button
                key={f.level}
                onClick={() => handleFontChange(f.level)}
                style={{
                  padding: '6px 14px',
                  borderRadius: 6,
                  border: `1px solid ${fontLevel === f.level ? 'var(--accent-primary, #4b8bf5)' : 'var(--border-secondary, rgba(127,127,127,0.35))'}`,
                  background: fontLevel === f.level ? 'color-mix(in srgb, var(--accent-primary, #4b8bf5) 18%, transparent)' : 'transparent',
                  color: 'var(--text-primary)',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        {/* 流式发送行为（对话进行中回车/Tab 的语义） */}
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>流式发送行为</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {SEND_BEHAVIOR_OPTIONS.map((o) => (
              <button
                key={o.value}
                onClick={() => handleSendBehaviorChange(o.value)}
                style={{
                  textAlign: 'left',
                  padding: '8px 14px',
                  borderRadius: 6,
                  border: `1px solid ${sendBehaviorMode === o.value ? 'var(--accent-primary, #4b8bf5)' : 'var(--border-secondary, rgba(127,127,127,0.35))'}`,
                  background: sendBehaviorMode === o.value ? 'color-mix(in srgb, var(--accent-primary, #4b8bf5) 18%, transparent)' : 'transparent',
                  color: 'var(--text-primary)',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                <div style={{ fontWeight: 600 }}>{o.label}</div>
                <div style={{ fontSize: 12, opacity: 0.7, marginTop: 2 }}>{o.hint}</div>
              </button>
            ))}
          </div>
        </div>

        {/* 提示词优化（editor-prompt-optimize 扩展） */}
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 4 }}>提示词优化</div>
          <div style={{ fontSize: 12, opacity: 0.7, marginBottom: 8 }}>
            点击输入框右上角的 💡 灯泡，将当前输入内容交给指定模型优化。
          </div>

          {/* 优化模型 */}
          <div style={{ fontSize: 12, marginBottom: 6 }}>优化模型</div>
          <select
            value={optimize.model}
            onChange={(e) => handleOptimizeModelChange(e.target.value)}
            style={{
              width: '100%',
              padding: '6px 10px',
              borderRadius: 6,
              border: '1px solid var(--border-secondary, rgba(127,127,127,0.35))',
              background: 'var(--bg-chat, #1e1e1e)',
              color: 'var(--text-primary)',
              fontSize: 13,
            }}
          >
            <option value="">跟随当前会话模型</option>
            {optimize.models.map((m) => (
              <option key={m.key} value={m.key}>{m.label}</option>
            ))}
          </select>

          {/* 推理强度 */}
          <div style={{ fontSize: 12, margin: '10px 0 6px' }}>推理强度</div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            <button
              onClick={() => handleOptimizeThinkingChange('')}
              style={{
                padding: '4px 10px',
                borderRadius: 6,
                border: `1px solid ${optimize.thinking === '' ? 'var(--accent-primary, #4b8bf5)' : 'var(--border-secondary, rgba(127,127,127,0.35))'}`,
                background: optimize.thinking === '' ? 'color-mix(in srgb, var(--accent-primary, #4b8bf5) 18%, transparent)' : 'transparent',
                color: 'var(--text-primary)',
                cursor: 'pointer',
                fontSize: 12,
              }}
            >
              跟随会话
            </button>
            {optimize.thinkingLevels.map((level) => (
              <button
                key={level}
                onClick={() => handleOptimizeThinkingChange(level)}
                style={{
                  padding: '4px 10px',
                  borderRadius: 6,
                  border: `1px solid ${optimize.thinking === level ? 'var(--accent-primary, #4b8bf5)' : 'var(--border-secondary, rgba(127,127,127,0.35))'}`,
                  background: optimize.thinking === level ? 'color-mix(in srgb, var(--accent-primary, #4b8bf5) 18%, transparent)' : 'transparent',
                  color: 'var(--text-primary)',
                  cursor: 'pointer',
                  fontSize: 12,
                }}
              >
                {level}
              </button>
            ))}
          </div>
          <div style={{ fontSize: 11, opacity: 0.6, marginTop: 6 }}>
            当前：{selectedOptimizeLabel} {optimize.thinking ? `· 推理 ${optimize.thinking}` : ''}
          </div>
        </div>
      </div>
    </div>
  );
};

export default SettingsView;
