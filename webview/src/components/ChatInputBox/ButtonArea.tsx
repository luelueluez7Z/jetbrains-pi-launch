import { useCallback, useMemo, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { ButtonAreaProps, ReasoningEffort } from './types';
import { ModelSelect, PlanModeSelect, ReasoningSelect } from './selectors';
import { useCliModels } from '../../hooks/providers/useCliModels';
import { useToolbarSelectorCompact } from './hooks/useToolbarSelectorCompact';
import { resolveProviderModels } from './resolveProviderModels';
import { sendBridgeEvent } from '../../utils/bridge';

/**
 * ButtonArea - Bottom toolbar component
 * Contains model selector, reasoning selector, plan mode, context preset, compact button,
 * prompt enhancer (💡), and send/stop button. 纯 pi：无 provider/mode 切换。
 */
export const ButtonArea = ({
  disabled = false,
  hasInputContent = false,
  isLoading = false,
  isEnhancing = false,
  selectedModel = '',
  currentProvider = 'pi',
  reasoningEffort = 'high',
  piThinkingLevels = [],
  onSubmit,
  onStop,
  onModelSelect,
  onReasoningChange,
  onEnhancePrompt,
}: ButtonAreaProps) => {
  const { t } = useTranslation();
  const isPi = currentProvider === 'pi';
  // 手动压缩会话上下文（等价 TUI 的 /compact）
  const handleCompactClick = useCallback(() => {
    sendBridgeEvent('compact_session', '');
  }, []);
  // const fileInputRef = useRef<HTMLInputElement>(null);
  const { cliModels, cliModelsLoading, cliModelsError, cliDefaultModel, cliCatalogHasEntries, refreshCliModels } = useCliModels(currentProvider);

  // 模型列表来源（纯 pi）：后端推送的 cliModels。
  const availableModels = useMemo(() => {
    return resolveProviderModels({
      provider: currentProvider,
      cliModels,
    });
  }, [currentProvider, cliModels]);

  // 纯 pi：无 provider 切换（cc-gui 遗留的静态目录已移除）
  useEffect(() => {
    const isDynamicProvider = currentProvider === 'pi';
    if (!isDynamicProvider) return;
    // 后端真实目录到达后才校正选中项，避免空列表时闪回。
    if (!cliCatalogHasEntries) return;
    if (cliModelsLoading) return;
    if (!availableModels.length || !onModelSelect) return;
    const exists = availableModels.some((model) => model.id === selectedModel);
    if (!exists) {
      onModelSelect(cliDefaultModel ?? availableModels[0].id);
    }
  }, [
    availableModels,
    currentProvider,
    onModelSelect,
    selectedModel,
    cliDefaultModel,
    cliCatalogHasEntries,
    cliModelsLoading,
  ]);

  /**
   * Handle submit button click
   */
  const handleSubmitClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onSubmit?.();
  }, [onSubmit]);

  /**
   * Handle stop button click
   */
  const handleStopClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onStop?.();
  }, [onStop]);

  /**
   * Handle model selection
   */
  const handleModelSelect = useCallback((modelId: string) => {
    onModelSelect?.(modelId);
  }, [onModelSelect]);

  /**
   * Handle reasoning depth selection
   */
  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    onReasoningChange?.(effort);
  }, [onReasoningChange]);

  /**
   * Handle enhance prompt button click
   */
  const handleEnhanceClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onEnhancePrompt?.();
  }, [onEnhancePrompt]);

  // Collapse selector labels for every CLI when left cluster is about to hit the send cluster (10px).
  const buttonAreaRef = useRef<HTMLDivElement>(null);
  const buttonAreaLeftRef = useRef<HTMLDivElement>(null);
  const buttonAreaRightRef = useRef<HTMLDivElement>(null);
  const selectorContentKey = [
    currentProvider,
    selectedModel,
    reasoningEffort,
    cliModelsLoading ? 'loading' : 'ready',
  ].join('|');
  const selectorsCompact = useToolbarSelectorCompact(
    buttonAreaRef,
    buttonAreaLeftRef,
    buttonAreaRightRef,
    selectorContentKey,
  );

  return (
    <div
      ref={buttonAreaRef}
      className={`button-area${selectorsCompact ? ' button-area--compact' : ''}`}
      data-provider={currentProvider}
    >
      {/* Left side: selectors（纯 pi：模型 / 推理强度 / Plan / 上下文挡位 / 压缩） */}
      <div ref={buttonAreaLeftRef} className="button-area-left">
        <ModelSelect
          value={selectedModel}
          onChange={handleModelSelect}
          models={availableModels}
          currentProvider={currentProvider}
          loading={cliModelsLoading}
          error={cliModelsError}
          onRetry={() => refreshCliModels(currentProvider)}
        />
        <ReasoningSelect value={reasoningEffort} onChange={handleReasoningChange} selectedModel={selectedModel} currentProvider={currentProvider} piThinkingLevels={piThinkingLevels} />
        {isPi && <PlanModeSelect />}
        {isPi && (
          <button
            className="has-tooltip"
            onClick={handleCompactClick}
            disabled={disabled || isLoading}
            data-tooltip="压缩上下文 (等价 /compact)"
            title="压缩上下文"
            style={{ border: 'none', background: 'transparent', cursor: 'pointer', padding: '4px', color: 'inherit', display: 'inline-flex', alignItems: 'center' }}
          >
            <span className="codicon codicon-archive" />
          </button>
        )}
      </div>

      {/* Right side: tool buttons */}
      <div ref={buttonAreaRightRef} className="button-area-right">
        {/* Enhance prompt button（优化提示词：pi 模式走 editor-prompt-optimize 扩展） */}
        <button
          className="enhance-prompt-button has-tooltip"
          onClick={handleEnhanceClick}
          disabled={disabled || !hasInputContent || isLoading || isEnhancing}
          data-tooltip="优化提示词"
        >
          <span className={`codicon ${isEnhancing ? 'codicon-loading codicon-modifier-spin' : 'codicon-light-bulb'}`} />
        </button>

        {/* Send/Stop button */}
        {isLoading ? (
          <button
            className="submit-button stop-button has-tooltip"
            onClick={handleStopClick}
            data-tooltip={t('chat.stopGeneration')}
          >
            <span className="codicon codicon-debug-stop" />
          </button>
        ) : (
          <button
            className="submit-button has-tooltip"
            onClick={handleSubmitClick}
            disabled={disabled || !hasInputContent}
            data-tooltip={t('chat.sendMessageEnter')}
          >
            <span className="codicon codicon-send" />
          </button>
        )}
      </div>
    </div>
  );
};

export default ButtonArea;
