import { type RefObject, useCallback, useLayoutEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { ChatInputBox } from './ChatInputBox';
import type {
  Attachment,
  ChatInputBoxHandle,
} from './ChatInputBox/types';
import { MessageList } from './MessageList';
import {
  SessionIdContext,
  SubagentHistoryContext,
  ToolResultRawContext,
} from '../contexts/SubagentContext';
import { useMessages } from '../contexts/MessagesContext';
import { useSession } from '../contexts/SessionContext';
import { useUIState } from '../contexts/UIStateContext';
import { extractMarkdownContent } from '../utils/copyUtils';
import type { ClaudeMessage, ToolResultBlock } from '../types';
import type { useMessageProcessing, useModelProviderState, useMessageQueue } from '../hooks';
import type { GetToolResultRawFn } from '../contexts/SubagentContext';
import { reconcileMessageKeys, type MessageKeySnapshot } from '../utils/messageUtils';

type SubagentHistoryMap = ReturnType<typeof useMessages>['subagentHistories'];
type ProviderState = ReturnType<typeof useModelProviderState>;
type MessageQueueValue = ReturnType<typeof useMessageQueue>['queue'];

export interface ChatScreenProps {
  // Computed message data
  mergedMessages: ClaudeMessage[];
  getMessageText: (message: ClaudeMessage) => string;
  getContentBlocks: ReturnType<typeof useMessageProcessing>['getContentBlocks'];
  findToolResult: (toolUseId?: string, messageIndex?: number) => ToolResultBlock | null;
  getToolResultRaw: GetToolResultRawFn;
  subagentHistoryCtxValue: SubagentHistoryMap;
  sessionIdCtxValue: { currentSessionId: string | null; currentProvider: string };

  // Refs
  chatInputRef: RefObject<ChatInputBoxHandle | null>;
  messagesContainerRef: RefObject<HTMLDivElement | null>;
  messagesEndRef: RefObject<HTMLDivElement | null>;
  inputAreaRef: RefObject<HTMLDivElement | null>;

  // Submit / interrupt / nav
  onSubmit: (content: string, attachments?: Attachment[]) => void;
  onInterrupt: () => void;
  onProviderSelect: (providerId: string) => void;

  // Model / provider state (slice from useModelProviderState)
  currentProvider: ProviderState['currentProvider'];
  selectedModel: ProviderState['selectedModel'];
  permissionMode: ProviderState['permissionMode'];
  selectedAgent: ProviderState['selectedAgent'];
  sdkStatusLoading: ProviderState['sdkStatusLoading'];
  sdkStatusError: ProviderState['sdkStatusError'];
  onRetrySdkStatus: ProviderState['retrySdkStatus'];
  currentSdkInstalled: ProviderState['currentSdkInstalled'];
  activeProviderConfig: ProviderState['activeProviderConfig'];
  claudeSettingsAlwaysThinkingEnabled: ProviderState['claudeSettingsAlwaysThinkingEnabled'];
  reasoningEffort: ProviderState['reasoningEffort'];
  piThinkingLevels: ProviderState['piThinkingLevels'];
  codexFastMode: ProviderState['codexFastMode'];
  streamingEnabledSetting: ProviderState['streamingEnabledSetting'];
  sendShortcut: ProviderState['sendShortcut'];
  autoOpenFileEnabled: ProviderState['autoOpenFileEnabled'];
  longContextEnabled: ProviderState['longContextEnabled'];
  usagePercentage: ProviderState['usagePercentage'];
  usageUsedTokens: ProviderState['usageUsedTokens'];
  usageMaxTokens: ProviderState['usageMaxTokens'];

  // Model handlers
  onModeSelect: ProviderState['handleModeSelect'];
  onModelSelect: ProviderState['handleModelSelect'];
  onAgentSelect: ProviderState['handleAgentSelect'];
  onReasoningChange: ProviderState['handleReasoningChange'];
  onCodexFastModeChange: ProviderState['handleCodexFastModeChange'];
  onToggleThinking: ProviderState['handleToggleThinking'];
  onStreamingEnabledChange: ProviderState['handleStreamingEnabledChange'];
  onAutoOpenFileEnabledChange: ProviderState['handleAutoOpenFileEnabledChange'];
  onLongContextChange: ProviderState['handleLongContextChange'];

  // Message queue
  messageQueue: MessageQueueValue;
  onRemoveFromQueue: (id: string) => void;
}

/**
 * 精简版聊天视图：消息列表 + 输入框。
 */
export const ChatScreen = ({
  mergedMessages, getMessageText, getContentBlocks, findToolResult, getToolResultRaw,
  subagentHistoryCtxValue, sessionIdCtxValue,
  chatInputRef, messagesContainerRef, messagesEndRef, inputAreaRef,
  onSubmit, onInterrupt, onProviderSelect,
  currentProvider, selectedModel, permissionMode, selectedAgent,
  sdkStatusLoading, sdkStatusError, onRetrySdkStatus, currentSdkInstalled,
  activeProviderConfig, claudeSettingsAlwaysThinkingEnabled,
  reasoningEffort, piThinkingLevels, codexFastMode, streamingEnabledSetting, sendShortcut, autoOpenFileEnabled,
  longContextEnabled, usagePercentage, usageUsedTokens, usageMaxTokens,
  onModeSelect, onModelSelect, onAgentSelect, onReasoningChange, onCodexFastModeChange, onToggleThinking,
  onStreamingEnabledChange,
  onAutoOpenFileEnabledChange, onLongContextChange,
  messageQueue, onRemoveFromQueue,
}: ChatScreenProps) => {
  const { t } = useTranslation();
  const { messages, status, loading, isThinking, streamingActive } = useMessages();
  const { currentSessionId } = useSession();
  const previousMessageKeySnapshotRef = useRef<MessageKeySnapshot | undefined>(undefined);
  const messageKeySnapshot = useMemo(
    () => reconcileMessageKeys(
      mergedMessages,
      previousMessageKeySnapshotRef.current,
      `${currentProvider}:${currentSessionId ?? 'active-session'}`,
    ),
    [currentProvider, currentSessionId, mergedMessages],
  );
  useLayoutEffect(() => {
    previousMessageKeySnapshotRef.current = messageKeySnapshot;
  }, [messageKeySnapshot]);
  const {
    contextInfo, setContextInfo,
    draftInput, setDraftInput,
    addToast,
  } = useUIState();

  const handleSubmit = useCallback((content: string, attachments?: Attachment[]) => {
    onSubmit(content, attachments);
  }, [onSubmit]);

  return (
    <>
      <div className="messages-shell">
        <div className="messages-container" ref={messagesContainerRef}>
          <SessionIdContext.Provider value={sessionIdCtxValue}>
            <SubagentHistoryContext.Provider value={subagentHistoryCtxValue}>
              <ToolResultRawContext.Provider value={getToolResultRaw}>
                <MessageList
                  messages={mergedMessages}
                  messageKeys={messageKeySnapshot.keys}
                  streamingActive={streamingActive}
                  isThinking={isThinking}
                  t={t}
                  getMessageText={getMessageText}
                  getContentBlocks={getContentBlocks}
                  findToolResult={findToolResult}
                  extractMarkdownContent={extractMarkdownContent}
                  messagesEndRef={messagesEndRef}
                  currentProvider={currentProvider}
                  currentSessionId={currentSessionId}
                />
              </ToolResultRawContext.Provider>
            </SubagentHistoryContext.Provider>
          </SessionIdContext.Provider>
        </div>
      </div>

      <div className="input-area" ref={inputAreaRef}>
        <ChatInputBox
          ref={chatInputRef}
          isLoading={loading}
          selectedModel={selectedModel}
          permissionMode={permissionMode}
          currentProvider={currentProvider}
          status={status}
          usagePercentage={usagePercentage}
          usageUsedTokens={usageUsedTokens}
          usageMaxTokens={usageMaxTokens}
          showUsage={true}
          alwaysThinkingEnabled={activeProviderConfig?.settingsConfig?.alwaysThinkingEnabled ?? claudeSettingsAlwaysThinkingEnabled}
          placeholder={sendShortcut === 'cmdEnter' ? t('chat.inputPlaceholderCmdEnter') : t('chat.inputPlaceholderEnter')}
          sdkInstalled={currentSdkInstalled}
          sdkStatusLoading={sdkStatusLoading}
          sdkStatusError={sdkStatusError !== null}
          onRetrySdkStatus={onRetrySdkStatus}
          value={draftInput}
          onInput={setDraftInput}
          onSubmit={handleSubmit}
          onStop={onInterrupt}
          onModeSelect={onModeSelect}
          onModelSelect={onModelSelect}
          onProviderSelect={onProviderSelect}
          reasoningEffort={reasoningEffort}
          piThinkingLevels={piThinkingLevels}
          onReasoningChange={onReasoningChange}
          codexFastMode={codexFastMode}
          onCodexFastModeChange={onCodexFastModeChange}
          onToggleThinking={onToggleThinking}
          streamingEnabled={streamingEnabledSetting}
          onStreamingEnabledChange={onStreamingEnabledChange}
          sendShortcut={sendShortcut}
          selectedAgent={selectedAgent}
          onAgentSelect={onAgentSelect}
          activeFile={contextInfo?.file}
          selectedLines={contextInfo?.startLine !== undefined && contextInfo?.endLine !== undefined
            ? (contextInfo.startLine === contextInfo.endLine
                ? `L${contextInfo.startLine}`
                : `L${contextInfo.startLine}-${contextInfo.endLine}`)
            : undefined}
          onClearContext={() => setContextInfo(null)}
          hasMessages={messages.length > 0}
          addToast={addToast}
          messageQueue={messageQueue}
          onRemoveFromQueue={onRemoveFromQueue}
          autoOpenFileEnabled={autoOpenFileEnabled}
          onAutoOpenFileEnabledChange={onAutoOpenFileEnabledChange}
          longContextEnabled={longContextEnabled}
          onLongContextChange={onLongContextChange}
        />
      </div>
    </>
  );
};
