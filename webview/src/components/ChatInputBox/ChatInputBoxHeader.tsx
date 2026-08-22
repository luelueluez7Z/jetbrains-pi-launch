import type { Attachment, SelectedAgent, QueuedMessage } from './types.js';

import { AttachmentList } from './AttachmentList.js';
import { ContextBar } from './ContextBar.js';
import { MessageQueue } from './MessageQueue.js';

export function ChatInputBoxHeader({
  currentProvider,
  attachments,
  onRemoveAttachment,
  activeFile,
  selectedLines,
  usagePercentage,
  usageUsedTokens,
  usageMaxTokens,
  showUsage,
  onClearContext,
  onAddAttachment,
  selectedAgent,
  onClearAgent,
  hasMessages,
  onRewind,
  statusPanelExpanded,
  onToggleStatusPanel,
  messageQueue,
  onRemoveFromQueue,
  onRecallFromQueue,
  autoOpenFileEnabled,
  onRequestEnableFileContext,
}: {
  currentProvider: string;
  attachments: Attachment[];
  onRemoveAttachment: (id: string) => void;
  activeFile?: string;
  selectedLines?: string;
  usagePercentage: number;
  usageUsedTokens?: number;
  usageMaxTokens?: number;
  showUsage: boolean;
  onClearContext?: () => void;
  onAddAttachment: (files: FileList) => void;
  selectedAgent?: SelectedAgent | null;
  onClearAgent: () => void;
  hasMessages: boolean;
  onRewind?: () => void;
  statusPanelExpanded: boolean;
  onToggleStatusPanel?: () => void;
  messageQueue?: QueuedMessage[];
  onRemoveFromQueue?: (id: string) => void;
  onRecallFromQueue?: (id: string) => void;
  autoOpenFileEnabled?: boolean;
  onRequestEnableFileContext?: () => void;
}) {
  return (
    <>
      {/* Message queue */}
      {messageQueue && messageQueue.length > 0 && (
        <MessageQueue
          queue={messageQueue}
          onRemove={onRemoveFromQueue ?? (() => {})}
          onRecall={onRecallFromQueue ?? (() => {})}
        />
      )}

      {/* Attachment list */}
      {attachments.length > 0 && (
        <AttachmentList attachments={attachments} onRemove={onRemoveAttachment} />
      )}

      {/* The upstream context/status bar contains Claude/Codex controls. Pi's
          TUI keeps this area compact; attachments and queued messages above
          remain available when they are actually present. */}
      {currentProvider !== 'pi' && (
        <ContextBar
          activeFile={activeFile}
          selectedLines={selectedLines}
          percentage={usagePercentage}
          usedTokens={usageUsedTokens}
          maxTokens={usageMaxTokens}
          showUsage={showUsage}
          onClearFile={onClearContext}
          onAddAttachment={onAddAttachment}
          selectedAgent={selectedAgent}
          onClearAgent={onClearAgent}
          currentProvider={currentProvider}
          hasMessages={hasMessages}
          onRewind={onRewind}
          statusPanelExpanded={statusPanelExpanded}
          onToggleStatusPanel={onToggleStatusPanel}
          autoOpenFileEnabled={autoOpenFileEnabled}
          onRequestEnableFileContext={onRequestEnableFileContext}
        />
      )}
    </>
  );
}
