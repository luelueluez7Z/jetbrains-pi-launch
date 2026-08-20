import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { sendBridgeEvent } from '../../../utils/bridge';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';

const RELATIVE_INLINE_BLOCK_STYLE: React.CSSProperties = { position: 'relative', display: 'inline-block' };
const CHEVRON_ICON_STYLE: React.CSSProperties = { fontSize: '10px', marginLeft: '2px' };
const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  zIndex: 10000,
  maxWidth: 'calc(100vw - 16px)',
  overflowX: 'hidden',
};

const DEFAULT_PRESETS = [200, 400, 1000];

interface ContextPresetState {
  currentK: number;    // 当前模型生效的 contextWindow（k）
  persistedK: number;  // 持久化挡位（k），-1 表示未持久化
  presets: number[];   // 可用挡位（k）
  modelKey: string;    // provider/modelId
}

/**
 * ContextPresetSelect - 上下文挡位选择器
 *
 * 调用的 pi 扩展: ~/.pi/agent/extensions/ctx-preset（/ctx 命令）。
 * 选择挡位后写 ~/.pi/agent/ctx-preset.json 并重启 pi 进程（单会话模式），
 * 扩展在 session_start 时自动应用持久化挡位，压缩触发点随之改变。
 */
export const ContextPresetSelect = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [state, setState] = useState<ContextPresetState>({
    currentK: 0,
    persistedK: -1,
    presets: DEFAULT_PRESETS,
    modelKey: '',
  });
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { positionedStyle, recalculate } = useDropdownPosition({
    buttonRef,
    dropdownRef,
    preferredAlignment: 'right',
  });

  // 从后端读取当前模型的挡位信息（模型变化后重新拉取）
  useEffect(() => {
    sendBridgeEvent('get_context_presets');

    window.updateContextPresets = (json: string) => {
      try {
        const data = JSON.parse(json) as Partial<ContextPresetState>;
        setState(prev => ({
          currentK: typeof data.currentK === 'number' ? data.currentK : prev.currentK,
          persistedK: typeof data.persistedK === 'number' ? data.persistedK : prev.persistedK,
          presets: Array.isArray(data.presets) && data.presets.length > 0 ? data.presets : prev.presets,
          modelKey: typeof data.modelKey === 'string' ? data.modelKey : prev.modelKey,
        }));
      } catch {
        // 忽略解析失败
      }
    };
    return () => {
      delete window.updateContextPresets;
    };
  }, []);

  const currentLabel = state.currentK > 0 ? `${state.currentK}K` : '—';
  const isVisible = state.currentK > 0;

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) recalculate();
  }, [isOpen, recalculate]);

  const handleSelect = useCallback((level: number) => {
    setIsOpen(false);
    if (level === state.currentK) return;
    sendBridgeEvent('set_context_preset', String(level));
  }, [state.currentK]);

  // 点击外部关闭
  useEffect(() => {
    if (!isOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
      }
    };
    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  useLayoutEffect(() => {
    if (isOpen) recalculate();
  }, [isOpen, recalculate]);

  if (!isVisible) return null;

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        title={state.modelKey ? `上下文挡位（${state.modelKey}）` : '上下文挡位'}
      >
        <span className="codicon codicon-edit" style={{ fontSize: '12px' }} />
        <span className="selector-button-text">{currentLabel}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{ ...DROPDOWN_STYLE, ...positionedStyle }}
        >
          {state.presets.map((level) => {
            const isCurrent = level === state.currentK;
            const isPersisted = level === state.persistedK;
            return (
              <div
                key={level}
                className={`selector-option ${isCurrent ? 'selected' : ''}`}
                onClick={() => handleSelect(level)}
                title={`将 ${state.modelKey || '当前模型'} 的上下文上限设为 ${level}k${isPersisted ? '（已持久化）' : ''}`}
              >
                <span className="codicon codicon-database" />
                <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
                  <span>
                    {level}K{isPersisted ? ' · 持久化' : ''}
                  </span>
                  <span className="mode-description">
                    {isCurrent ? '当前生效' : '切换后重启会话生效'}
                  </span>
                </div>
                {isCurrent && <span className="codicon codicon-check check-mark" />}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default ContextPresetSelect;
