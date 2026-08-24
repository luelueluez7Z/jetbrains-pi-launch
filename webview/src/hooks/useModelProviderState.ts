import { useCallback, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { sendBridgeEvent } from '../utils/bridge';
import type { PermissionMode, ReasoningEffort } from '../components/ChatInputBox/types';
import { usePiProvider } from './providers/usePiProvider';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';

export type ViewMode = 'chat' | 'history' | 'settings';

export interface UseModelProviderStateOptions {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  t: TFunction;
}

/**
 * 纯 pi 的 provider 状态编排：插件只接本地 pi（pi --mode rpc），
 * currentProvider 固定为 'pi'，不再有 claude/codex/grok/kimi/opencode 分支。
 *
 * 前端不持有权威数据：模型/推理强度/权限等均由后端推送
 * （applyBackendTabState / onModelConfirmed / updateThinkingLevels），
 * 本 hook 只维护本地选中态并转发用户选择事件给后端。
 */
export function useModelProviderState({ addToast, t }: UseModelProviderStateOptions) {
  // pi 固定 provider（后端权威，这里仅作标记供 UI 分支判断）
  const currentProvider = 'pi';
  const [permissionMode, setPermissionMode] = useState<PermissionMode>('default');
  // 推理强度：后端推送当前值，选择时转发 set_reasoning_effort
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('high');

  // 供 window callbacks（stable identity）读取当前 provider
  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;

  const pi = usePiProvider();
  const usage = useUsageTracking();
  const settings = useProviderSettings({ addToast, t });
  const { selectedPiModel, setSelectedPiModel } = pi;

  // 模型选择：转发给后端 set_model（后端 modelKey 契约：provider::id）
  const handleModelSelect = useCallback((modelId: string) => {
    setSelectedPiModel(modelId);
    sendBridgeEvent('set_model', modelId);
  }, [setSelectedPiModel]);

  // 推理强度选择：转发给后端 set_reasoning_effort
  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    setReasoningEffort(effort);
    sendBridgeEvent('set_reasoning_effort', effort);
  }, []);

  return {
    // pi provider 状态
    ...pi,
    // usage 状态
    ...usage,
    // 设置状态（streaming/sendShortcut/autoOpenFile/agent）
    ...settings,
    // 跨切面状态
    currentProvider,
    permissionMode, setPermissionMode,
    selectedModel: selectedPiModel,
    reasoningEffort, setReasoningEffort,
    currentProviderRef,
    // 处理器
    handleModelSelect,
    handleReasoningChange,
  };
}
