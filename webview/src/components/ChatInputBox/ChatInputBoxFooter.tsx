import type { TFunction } from 'i18next';
import type { DropdownItemData, DropdownPosition, ReasoningEffort } from './types.js';
import type { TooltipState } from './hooks/useTooltip.js';
import { ButtonArea } from './ButtonArea.js';
import { CompletionDropdown } from './Dropdown/index.js';
import { PromptEnhancerDialog } from './PromptEnhancerDialog.js';

interface CompletionController {
  isOpen: boolean;
  position: DropdownPosition | null;
  items: DropdownItemData[];
  activeIndex: number;
  loading: boolean;
  close: () => void;
  selectIndex: (index: number) => void;
  handleMouseEnter: (index: number) => void;
}

export function ChatInputBoxFooter({
  disabled,
  hasInputContent,
  isLoading,
  isEnhancing,
  selectedModel,
  currentProvider,
  reasoningEffort,
  piThinkingLevels,
  onSubmit,
  onStop,
  onModelSelect,
  onReasoningChange,
  onEnhancePrompt,
  fileCompletion,
  commandCompletion,
  agentCompletion,
  dollarCommandCompletion,
  tooltip,
  promptEnhancer,
  t,
}: {
  disabled: boolean;
  hasInputContent: boolean;
  isLoading: boolean;
  isEnhancing: boolean;
  selectedModel: string;
  currentProvider: string;
  reasoningEffort: ReasoningEffort;
  piThinkingLevels?: ReasoningEffort[];
  onSubmit: () => void;
  onStop?: () => void;
  onModelSelect?: (modelId: string) => void;
  onReasoningChange?: (effort: ReasoningEffort) => void;
  onEnhancePrompt: () => void;
  fileCompletion: CompletionController;
  commandCompletion: CompletionController;
  agentCompletion: CompletionController;
  dollarCommandCompletion?: CompletionController;
  tooltip: TooltipState | null;
  promptEnhancer: {
    isOpen: boolean;
    isLoading: boolean;
    originalPrompt: string;
    enhancedPrompt: string;
    usageInfo?: {
      provider: string | null;
      model: string | null;
      resolutionSource: 'manual' | 'auto' | 'unavailable' | null;
    } | null;
    onUseEnhanced: () => void;
    onKeepOriginal: () => void;
    onClose: () => void;
    onOpenSettings?: () => void;
  };
  t: TFunction;
}) {
  return (
    <>
      {/* Bottom button area（纯 pi） */}
      <ButtonArea
        disabled={disabled || isLoading}
        hasInputContent={hasInputContent}
        isLoading={isLoading}
        isEnhancing={isEnhancing}
        selectedModel={selectedModel}
        currentProvider={currentProvider}
        reasoningEffort={reasoningEffort}
        piThinkingLevels={piThinkingLevels}
        onSubmit={onSubmit}
        onStop={onStop}
        onModelSelect={onModelSelect}
        onReasoningChange={onReasoningChange}
        onEnhancePrompt={onEnhancePrompt}
      />

      {/* @ file reference dropdown menu */}
      <CompletionDropdown
        isVisible={fileCompletion.isOpen}
        position={fileCompletion.position}
        items={fileCompletion.items}
        selectedIndex={fileCompletion.activeIndex}
        loading={fileCompletion.loading}
        emptyText={t('chat.noMatchingFiles')}
        onClose={fileCompletion.close}
        onSelect={(_, index) => fileCompletion.selectIndex(index)}
        onMouseEnter={fileCompletion.handleMouseEnter}
      />

      {/* / slash command dropdown menu */}
      <CompletionDropdown
        isVisible={commandCompletion.isOpen}
        position={commandCompletion.position}
        width={450}
        items={commandCompletion.items}
        selectedIndex={commandCompletion.activeIndex}
        loading={commandCompletion.loading}
        emptyText={t('chat.noMatchingCommands')}
        onClose={commandCompletion.close}
        onSelect={(_, index) => commandCompletion.selectIndex(index)}
        onMouseEnter={commandCompletion.handleMouseEnter}
      />

      {/* # agent selection dropdown menu */}
      <CompletionDropdown
        isVisible={agentCompletion.isOpen}
        position={agentCompletion.position}
        width={350}
        items={agentCompletion.items}
        selectedIndex={agentCompletion.activeIndex}
        loading={agentCompletion.loading}
        emptyText={t('chat.noAvailableAgents')}
        onClose={agentCompletion.close}
        onSelect={(_, index) => agentCompletion.selectIndex(index)}
        onMouseEnter={agentCompletion.handleMouseEnter}
      />

      {/* $ command dropdown menu */}
      {dollarCommandCompletion && (
        <CompletionDropdown
          isVisible={dollarCommandCompletion.isOpen}
          position={dollarCommandCompletion.position}
          width={400}
          items={dollarCommandCompletion.items}
          selectedIndex={dollarCommandCompletion.activeIndex}
          loading={dollarCommandCompletion.loading}
          emptyText={t('chat.noMatchingCommands')}
          onClose={dollarCommandCompletion.close}
          onSelect={(_, index) => dollarCommandCompletion.selectIndex(index)}
          onMouseEnter={dollarCommandCompletion.handleMouseEnter}
        />
      )}

      {/* Floating Tooltip (uses Portal or Fixed positioning to break overflow limit) */}
      {tooltip && tooltip.visible && (() => {
        const tooltipStyle: React.CSSProperties = {
          top: `${tooltip.top}px`,
          left: `${tooltip.left}px`,
          width: tooltip.width ? `${tooltip.width}px` : undefined,
          // @ts-expect-error CSS custom properties
          '--tooltip-tx': tooltip.tx || '-50%',
          '--arrow-left': tooltip.arrowLeft || '50%',
        };
        return (
        <div
          className={`tooltip-popup ${tooltip.isBar ? 'tooltip-bar' : ''}`}
          style={tooltipStyle}
        >
          {tooltip.text}
        </div>
        );
      })()}

      {/* Prompt enhancement is not part of Pi/TUI. */}
      {currentProvider !== 'pi' && (
        <PromptEnhancerDialog
          isOpen={promptEnhancer.isOpen}
          isLoading={promptEnhancer.isLoading}
          originalPrompt={promptEnhancer.originalPrompt}
          enhancedPrompt={promptEnhancer.enhancedPrompt}
          usageInfo={promptEnhancer.usageInfo}
          onUseEnhanced={promptEnhancer.onUseEnhanced}
          onKeepOriginal={promptEnhancer.onKeepOriginal}
          onClose={promptEnhancer.onClose}
          onOpenSettings={promptEnhancer.onOpenSettings}
        />
      )}
    </>
  );
}
