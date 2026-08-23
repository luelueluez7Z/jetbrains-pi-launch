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

interface PlanModeState {
  active: boolean; // Plan 模式是否激活
  text: string;    // plan-mode 扩展子状态（plan active / ready / saved / implementing），空串未激活
}

/**
 * PlanModeSelect - Plan 模式切换器（pi 模式）
 *
 * 通过 pi-plan-mode 扩展（@narumitw/pi-plan-mode，/plan 命令）切换 Plan 模式：
 * - 普通模式：全工具访问，默认行为
 * - Plan 模式：只读探索 + 规划（edit/write 被扩展阻断，agent 只能读 + 提问）
 *
 * 当前状态由后端推送（updatePlanMode，来源 pi-plan-mode 扩展的 setStatus），
 * 前端不持有权威数据；选择后发 bridge 事件 set_plan_mode 由后端执行 /plan start|exit。
 */
export const PlanModeSelect = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [state, setState] = useState<PlanModeState>({ active: false, text: '' });
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { positionedStyle, recalculate } = useDropdownPosition({
    buttonRef,
    dropdownRef,
    preferredAlignment: 'right',
  });

  // 从后端读取/订阅 Plan 模式状态（会话恢复、切换时由后端推送）
  useEffect(() => {
    window.updatePlanMode = (json: string) => {
      try {
        const data = JSON.parse(json) as Partial<PlanModeState>;
        setState(prev => ({
          active: typeof data.active === 'boolean' ? data.active : prev.active,
          text: typeof data.text === 'string' ? data.text : prev.text,
        }));
      } catch {
        // 忽略解析失败
      }
    };
    return () => {
      delete window.updatePlanMode;
    };
  }, []);

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) recalculate();
  }, [isOpen, recalculate]);

  const handleSelect = useCallback((mode: 'plan' | 'normal') => {
    setIsOpen(false);
    if ((mode === 'plan') === state.active) return;
    sendBridgeEvent('set_plan_mode', mode);
  }, [state.active]);

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

  const activeLabel = state.active ? 'Plan' : '普通';
  // 激活时在按钮上显示子状态（plan active/ready/saved/implementing），更直观
  const statusSuffix = state.active && state.text ? ` · ${state.text.replace('plan ', '')}` : '';

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        title="模式：普通 / Plan（pi-plan-mode 扩展）"
      >
        <span className={`codicon ${state.active ? 'codicon-telescope' : 'codicon-comment-discussion'}`} style={{ fontSize: '12px' }} />
        <span className="selector-button-text">
          {activeLabel}{statusSuffix}
        </span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{ ...DROPDOWN_STYLE, ...positionedStyle }}
        >
          <div
            className={`selector-option ${!state.active ? 'selected' : ''}`}
            onClick={() => handleSelect('normal')}
            title="退出 Plan 模式，恢复全部工具访问"
          >
            <span className="codicon codicon-comment-discussion" />
            <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
              <span>普通模式</span>
              <span className="mode-description">全工具访问，默认模式</span>
            </div>
            {!state.active && <span className="codicon codicon-check check-mark" />}
          </div>
          <div
            className={`selector-option ${state.active ? 'selected' : ''}`}
            onClick={() => handleSelect('plan')}
            title="进入 Plan 模式（只读探索 + 规划，pi-plan-mode 扩展）"
          >
            <span className="codicon codicon-telescope" />
            <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
              <span>Plan 模式{state.active && state.text ? `（${state.text.replace('plan ', '')}）` : ''}</span>
              <span className="mode-description">只读探索 + 规划，禁止编辑</span>
            </div>
            {state.active && <span className="codicon codicon-check check-mark" />}
          </div>
        </div>
      )}
    </div>
  );
};

export default PlanModeSelect;
