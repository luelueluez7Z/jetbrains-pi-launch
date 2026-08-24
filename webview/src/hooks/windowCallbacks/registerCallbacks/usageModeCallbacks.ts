/**
 * usageModeCallbacks.ts
 *
 * Registers window bridge callbacks for usage statistics, permission modes, and
 * model/provider updates: onUsageUpdate, onModeChanged, onModeReceived,
 * onModelChanged, onModelConfirmed, updateActiveProvider, updateThinkingEnabled,
 * updateStreamingEnabled, updateSendShortcut, updateAutoOpenFileEnabled.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode, ReasoningEffort } from '../../../components/ChatInputBox/types';
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';
import { clampPermissionDialogTimeoutSeconds } from '../../../utils/permissionDialogTimeout';

export function registerUsageModeCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setPermissionMode,
    setSelectedPiModel,
    setReasoningEffort,
    setPiThinkingLevels,
    setActiveProviderConfig,
    setStreamingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setPermissionDialogTimeoutSeconds,
  } = options;

  window.onUsageUpdate = (json) => {
    try {
      const data = JSON.parse(json);
      if (typeof data.percentage === 'number') {
        const used =
          typeof data.usedTokens === 'number'
            ? data.usedTokens
            : typeof data.totalTokens === 'number'
              ? data.totalTokens
              : undefined;
        const max =
          typeof data.maxTokens === 'number'
            ? data.maxTokens
            : typeof data.limit === 'number'
              ? data.limit
              : undefined;

        if (used !== undefined && max !== undefined && used > max * 2) {
          console.warn(
            '[Frontend] Usage data may be incorrect: used=' + used + ', max=' + max,
          );
        }

        const safePercentage = Math.max(0, Math.min(100, data.percentage));
        setUsagePercentage(safePercentage);
        setUsageUsedTokens(used);
        setUsageMaxTokens(max);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse usage update:', error);
    }
  };

  if (typeof window.__pendingUsageUpdate === 'string') {
    const pending = window.__pendingUsageUpdate;
    delete window.__pendingUsageUpdate;
    window.onUsageUpdate(pending);
  }

  const updateMode = (mode?: PermissionMode) => {
    if (mode === 'default' || mode === 'plan') {
      setPermissionMode(mode);
    }
  };

  window.onModeChanged = (mode) => updateMode(mode as PermissionMode);
  window.onModeReceived = (mode) => updateMode(mode as PermissionMode);

  // 插件只接 pi：模型选择直接更新 pi 模型
  window.onModelChanged = (modelId) => {
    setSelectedPiModel(modelId);
  };

  window.onModelConfirmed = (modelId, _provider) => {
    setSelectedPiModel(modelId);
  };

  window.applyBackendTabState = (json: string) => {
    try {
      const state = JSON.parse(json) as Record<string, unknown>;
      const provider = state.provider;
      // 后端权威状态恢复：仅接受 pi（前端固定 provider）
      if (provider !== 'pi') {
        throw new Error('invalid provider');
      }

      if (typeof state.model === 'string' && state.model.length > 0) {
        setSelectedPiModel(state.model);
      }

      updateMode(state.permissionMode as PermissionMode | undefined);

      const reasoningValues: ReasoningEffort[] = ['off', 'low', 'medium', 'high', 'xhigh', 'max'];
      if (reasoningValues.includes(state.reasoningEffort as ReasoningEffort)) {
        setReasoningEffort(state.reasoningEffort as ReasoningEffort);
      }
      if (Array.isArray(state.piThinkingLevels)) {
        const levels = state.piThinkingLevels.filter((level): level is ReasoningEffort =>
          reasoningValues.includes(level as ReasoningEffort),
        );
        setPiThinkingLevels(levels);
      }
    } catch (error) {
      console.error('[Frontend] Failed to apply backend tab state:', error);
    }
  };

  if (typeof window.__pendingBackendTabState === 'string') {
    const pending = window.__pendingBackendTabState;
    delete window.__pendingBackendTabState;
    window.applyBackendTabState(pending);
  }

  window.updateActiveProvider = (jsonStr: string) => {
    try {
      const provider = JSON.parse(jsonStr);
      setActiveProviderConfig(provider);
    } catch (error) {
      console.error('[Frontend] Failed to parse active provider in App:', error);
    }
  };

  window.updateThinkingEnabled = (jsonStr: string) => {
    // cc-gui 的 Claude 专属开关，pi 无此概念——忽略
    void jsonStr;
  };

  window.updateStreamingEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setStreamingEnabledSetting(data.streamingEnabled ?? true);
    } catch (error) {
      console.error('[Frontend] Failed to parse streaming enabled:', error);
    }
  };

  window.updateSendShortcut = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      if (data.sendShortcut === 'enter' || data.sendShortcut === 'cmdEnter') {
        setSendShortcut(data.sendShortcut);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse send shortcut:', error);
    }
  };

  window.updateAutoOpenFileEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setAutoOpenFileEnabled(data.autoOpenFileEnabled ?? false);
    } catch (error) {
      console.error('[Frontend] Failed to parse auto open file enabled:', error);
    }
  };

  window.updatePermissionDialogTimeout = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setPermissionDialogTimeoutSeconds(clampPermissionDialogTimeoutSeconds(data.permissionDialogTimeoutSeconds));
    } catch (error) {
      const errorName = error instanceof Error ? error.name : 'UnknownError';
      console.error(`[Frontend] Failed to parse permission dialog timeout payload: ${errorName}`);
    }
  };

  // Drain any pending settings that arrived before callback registration
  drainPendingSettings();
  // Kick off initial settings requests
  startInitialSettingsRequest();
}
