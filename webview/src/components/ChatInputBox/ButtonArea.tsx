import { useCallback, useMemo, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { ButtonAreaProps, CodexFastMode, PermissionMode, ReasoningEffort } from './types';
import { CodexFastModeSelect, ConfigSelect, ContextPresetSelect, ModelSelect, ModeSelect, ProviderSelect, ReasoningSelect } from './selectors';
import { useCliModels } from '../../hooks/providers/useCliModels';
import { useToolbarSelectorCompact } from './hooks/useToolbarSelectorCompact';
import { resolveProviderModels } from './resolveProviderModels';

/**
 * ButtonArea - Bottom toolbar component
 * Contains mode selector, model selector, attachment button, prompt enhancer button, send/stop button
 */
export const ButtonArea = ({
  disabled = false,
  hasInputContent = false,
  isLoading = false,
  isEnhancing = false,
  selectedModel = 'claude-sonnet-4-7',
  permissionMode = 'default',
  currentProvider = 'claude',
  reasoningEffort = 'high',
  piThinkingLevels = [],
  codexFastMode = 'normal',
  onSubmit,
  onStop,
  onModeSelect,
  onModelSelect,
  onProviderSelect,
  onReasoningChange,
  onCodexFastModeChange,
  onEnhancePrompt,
  alwaysThinkingEnabled = false,
  onToggleThinking,
  streamingEnabled = true,
  onStreamingEnabledChange,
  selectedAgent,
  onAgentSelect,
  onOpenAgentSettings,
  onAddModel,
  longContextEnabled = true,
  onLongContextChange,
}: ButtonAreaProps) => {
  const { t } = useTranslation();
  const isPi = currentProvider === 'pi';
  // const fileInputRef = useRef<HTMLInputElement>(null);
  const { cliModels, cliModelsLoading, cliModelsError, cliDefaultModel, cliCatalogHasEntries, refreshCliModels } = useCliModels(currentProvider);

  // Select model list based on current provider. Pi models come from the
  // backend catalog (updateModels); localStorage custom models / claude mapping
  // were cc-gui concepts and are removed.
  const availableModels = useMemo(() => {
    return resolveProviderModels({
      provider: currentProvider,
      cliModels,
      cliCatalogHasEntries,
    });
  }, [currentProvider, cliModels, cliCatalogHasEntries]);

  // When a dynamic model catalog arrives, ensure selection is a real entry.
  useEffect(() => {
    const isDynamicProvider = currentProvider === 'kimi' || currentProvider === 'opencode'
      || currentProvider === 'pi' || currentProvider === 'codex'
      || currentProvider === 'grok';
    if (!isDynamicProvider) return;
    // Only correct once a *real* catalog arrived. Static fallback lists
    // (OPENCODE_MODELS = just "opencode-default", CODEX built-ins, …) must not
    // clobber the user's choice — especially when ChatScreen remounts after
    // leaving history and briefly shows the fallback before the cache/fetch
    // lands.
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
   * Handle mode selection
   */
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    onModeSelect?.(mode);
  }, [onModeSelect]);

  /**
   * Handle model selection
   */
  const handleModelSelect = useCallback((modelId: string) => {
    onModelSelect?.(modelId);
  }, [onModelSelect]);

  /**
   * Handle provider selection
   */
  const handleProviderSelect = useCallback((providerId: string) => {
    onProviderSelect?.(providerId);
  }, [onProviderSelect]);

  /**
   * Handle reasoning depth selection
   */
  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    onReasoningChange?.(effort);
  }, [onReasoningChange]);

  /**
   * Handle Codex speed mode selection
   */
  const handleCodexFastModeChange = useCallback((mode: CodexFastMode) => {
    onCodexFastModeChange?.(mode);
  }, [onCodexFastModeChange]);

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
    permissionMode,
    reasoningEffort,
    codexFastMode,
    selectedAgent?.id ?? '',
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
      {/* Left side: selectors */}
      <div ref={buttonAreaLeftRef} className="button-area-left">
        {!isPi && (
          <ConfigSelect
            alwaysThinkingEnabled={alwaysThinkingEnabled}
            onToggleThinking={onToggleThinking}
            streamingEnabled={streamingEnabled}
            onStreamingEnabledChange={onStreamingEnabledChange}
            selectedAgent={selectedAgent}
            onAgentSelect={onAgentSelect}
            onOpenAgentSettings={onOpenAgentSettings}
            currentProvider={currentProvider}
          />
        )}
        {!isPi && (
          <ProviderSelect
            value={currentProvider}
            onChange={handleProviderSelect}
            compact
          />
        )}
        {!isPi && <ModeSelect value={permissionMode} onChange={handleModeSelect} provider={currentProvider} />}
        <ModelSelect
          value={selectedModel}
          onChange={handleModelSelect}
          models={availableModels}
          currentProvider={currentProvider}
          loading={cliModelsLoading}
          error={cliModelsError}
          onRetry={() => refreshCliModels(currentProvider)}
          onAddModel={isPi ? undefined : onAddModel}
          longContextEnabled={isPi ? false : longContextEnabled}
          onLongContextChange={isPi ? undefined : onLongContextChange}
        />
        <ReasoningSelect value={reasoningEffort} onChange={handleReasoningChange} selectedModel={selectedModel} currentProvider={currentProvider} piThinkingLevels={piThinkingLevels} />
        {isPi && <ContextPresetSelect />}
        {!isPi && currentProvider === 'codex' && (
          <CodexFastModeSelect value={codexFastMode} onChange={handleCodexFastModeChange} />
        )}
      </div>

      {/* Right side: tool buttons */}
      <div ref={buttonAreaRightRef} className="button-area-right">
        {!isPi && <div className="button-divider" />}

        {/* Enhance prompt button */}
        {!isPi && (
          <button
            className="enhance-prompt-button has-tooltip"
            onClick={handleEnhanceClick}
            disabled={disabled || !hasInputContent || isLoading || isEnhancing}
            data-tooltip={`${t('promptEnhancer.tooltip')} (${t('promptEnhancer.shortcut')})`}
          >
            <span className={`codicon ${isEnhancing ? 'codicon-loading codicon-modifier-spin' : 'codicon-sparkle'}`} />
          </button>
        )}

        {/* Send/Stop button */}
        {isLoading ? (
          <button
            className="submit-button stop-button"
            onClick={handleStopClick}
            title={t('chat.stopGeneration')}
          >
            <span className="codicon codicon-debug-stop" />
          </button>
        ) : (
          <button
            className="submit-button"
            onClick={handleSubmitClick}
            disabled={disabled || !hasInputContent}
            title={t('chat.sendMessageEnter')}
          >
            <span className="codicon codicon-send" />
          </button>
        )}
      </div>
    </div>
  );
};

export default ButtonArea;
