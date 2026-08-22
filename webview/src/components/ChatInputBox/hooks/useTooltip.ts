import { useCallback, useRef, useState } from 'react';
import { getAppViewport } from '../../../utils/viewport';

export interface TooltipState {
  visible: boolean;
  text: string;
  top: number;
  left: number;
  tx?: string; // transform-x value
  arrowLeft?: string; // arrow left position
  width?: number; // width of the tooltip
  isBar?: boolean; // whether to show as a bar
}

interface UseTooltipReturn {
  /** Current tooltip state */
  tooltip: TooltipState | null;
  /** Handle mouse over to show tooltip */
  handleMouseOver: (e: React.MouseEvent) => void;
  /** Handle mouse leave to hide tooltip */
  handleMouseLeave: () => void;
}

// 触发 tooltip 的目标：带 has-tooltip + data-tooltip 的元素（压缩按钮、发送/停止、
// ChatHeader 按钮、context-tool-btn、enhance-prompt-button 等）统一走 JS 浮层。
// .context-item 排除：它用 CSS ::after 实现 tooltip（context-bar.css），避免双提示。
const TOOLTIP_TARGET_SELECTOR = '.has-tooltip[data-tooltip]:not(.context-item)';

// 悬停延迟显示时间（ms）：避免鼠标扫过时闪烁，几秒内停留才显示
const TOOLTIP_DELAY_MS = 500;

/**
 * useTooltip - Manage tooltip state for hoverable elements
 *
 * Shows a floating tooltip when hovering over elements with `.has-tooltip` class,
 * with smart positioning to avoid viewport overflow. Uses fixed positioning to
 * break out of overflow constraints in the input box container.
 */
export function useTooltip(): UseTooltipReturn {
  const [tooltip, setTooltip] = useState<TooltipState | null>(null);
  // 延迟显示定时器：悬停后停留一段时间才显示（防闪烁），移开即取消
  const showTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  /**
   * Handle mouse over to show tooltip (small floating popup style)
   */
  const handleMouseOver = useCallback((e: React.MouseEvent) => {
    const target = e.target as HTMLElement;
    const triggerEl = target.closest(TOOLTIP_TARGET_SELECTOR);

    // 先取消上一个待显示定时器（鼠标在元素间移动时重置延迟）
    if (showTimerRef.current) {
      clearTimeout(showTimerRef.current);
      showTimerRef.current = null;
    }

    if (triggerEl) {
      const text = triggerEl.getAttribute('data-tooltip');
      if (!text) return;
      // 延迟显示：悬停停留 TOOLTIP_DELAY_MS 后才弹出
      showTimerRef.current = setTimeout(() => {
        showTimerRef.current = null;
        // Use small floating tooltip (same effect as context-item)
        const rect = triggerEl.getBoundingClientRect();
        // Use #app's rect as reference - both rects are in the same coordinate space
        const { width: viewportWidth, top: viewportTop, left: viewportLeft, fixedPosDivisor } = getAppViewport();
        const triggerCenterX = rect.left - viewportLeft + rect.width / 2; // Trigger center X coordinate (relative to #app)

        // Estimate tooltip width (based on text length)
        const estimatedTooltipWidth = Math.min(text.length * 7 + 24, 400);
        const tooltipHalfWidth = estimatedTooltipWidth / 2;

        let tooltipLeft = triggerCenterX; // Tooltip base point (default centered)
        let tx = '-50%'; // Tooltip horizontal offset (default centered)
        let arrowLeft = '50%'; // Arrow position (relative to tooltip, default middle)

        // Boundary detection: prevent tooltip left overflow
        if (triggerCenterX - tooltipHalfWidth < 10) {
          // Near left edge: tooltip left-aligned
          tooltipLeft = 10; // Tooltip left edge 10px from viewport
          tx = '0'; // Tooltip no offset
          arrowLeft = `${triggerCenterX - 10}px`; // Arrow points to trigger center
        }
        // Boundary detection: prevent tooltip right overflow
        else if (triggerCenterX + tooltipHalfWidth > viewportWidth - 10) {
          // Near right edge: tooltip right-aligned
          tooltipLeft = viewportWidth - 10; // Tooltip right edge 10px from viewport
          tx = '-100%'; // Tooltip offset left by full width
          arrowLeft = `${triggerCenterX - (viewportWidth - 10) + estimatedTooltipWidth}px`; // Arrow points to trigger center
        }
        // Normal case: tooltip centered
        else {
          arrowLeft = '50%'; // Arrow in tooltip middle
        }

        setTooltip({
          visible: true,
          text,
          top: (rect.top - viewportTop) / fixedPosDivisor,
          left: tooltipLeft / fixedPosDivisor,
          tx,
          arrowLeft,
          isBar: false,
        });
      }, TOOLTIP_DELAY_MS);
    }
    // 非目标元素：不隐藏（保持现有 tooltip，由 mouseleave 统一隐藏），避免鼠标扫过相邻元素时闪烁
  }, []);

  /**
   * Handle mouse leave to hide tooltip
   */
  const handleMouseLeave = useCallback(() => {
    if (showTimerRef.current) {
      clearTimeout(showTimerRef.current);
      showTimerRef.current = null;
    }
    setTooltip(null);
  }, []);

  return {
    tooltip,
    handleMouseOver,
    handleMouseLeave,
  };
}
