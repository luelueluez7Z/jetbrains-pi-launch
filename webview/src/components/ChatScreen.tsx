import { type RefObject, useCallback, useLayoutEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { ChatInputBox } from './ChatInputBox';
import './ChatScreen.css';
import type {
  Attachment,
  ChatInputBoxHandle,
} from './ChatInputBox/types';
import { MessageList } from './MessageList';
import { StatusPanel } from './StatusPanel';
import type { TodoItem, SubagentInfo } from '../types';
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
import type { SendBehavior, SendBehaviorMode } from '../utils/sendBehavior';

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
  onSubmit: (content: string, attachments?: Attachment[], behavior?: SendBehavior) => void;
  onInterrupt: () => void;
  /** 流式发送键位模式（透传给输入框，决定回车/Tab 语义） */
  sendBehaviorMode?: SendBehaviorMode;

  // Model / provider state (slice from useModelProviderState，纯 pi)
  currentProvider: ProviderState['currentProvider'];
  selectedModel: ProviderState['selectedModel'];
  reasoningEffort: ProviderState['reasoningEffort'];
  piThinkingLevels: ProviderState['piThinkingLevels'];
  sendShortcut: ProviderState['sendShortcut'];

  // Model handlers
  onModelSelect: ProviderState['handleModelSelect'];
  onReasoningChange: ProviderState['handleReasoningChange'];

  // Message queue
  messageQueue: MessageQueueValue;
  onRemoveFromQueue: (id: string) => void;
  onRecallFromQueue: (id: string) => void;

  // StatusPanel (todos + subagents)
  todos: TodoItem[];
  subagents: SubagentInfo[];
  statusPanelExpanded: boolean;
  onToggleStatusPanel: () => void;
}

/**
 * 精简版聊天视图：消息列表 + 输入框。
 */
export const ChatScreen = ({
  mergedMessages, getMessageText, getContentBlocks, findToolResult, getToolResultRaw,
  subagentHistoryCtxValue, sessionIdCtxValue,
  chatInputRef, messagesContainerRef, messagesEndRef, inputAreaRef,
  onSubmit, onInterrupt,
  sendBehaviorMode,
  currentProvider, selectedModel,
  reasoningEffort, piThinkingLevels, sendShortcut,
  onModelSelect, onReasoningChange,
  messageQueue, onRemoveFromQueue, onRecallFromQueue,
  todos, subagents, statusPanelExpanded, onToggleStatusPanel,
}: ChatScreenProps) => {
  const { t } = useTranslation();
  const { status, loading, isThinking, streamingActive, subagentHistories } = useMessages();
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
    draftInput, setDraftInput,
    addToast,
  } = useUIState();

  const handleSubmit = useCallback((content: string, attachments?: Attachment[], behavior?: SendBehavior) => {
    onSubmit(content, attachments, behavior);
  }, [onSubmit]);

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {currentProvider !== 'pi' ? (
        <div className="pichat-loading">
          <div className="pichat-loading-spinner" />
          <div>正在加载中…</div>
        </div>
      ) : (
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

      <StatusPanel
        todos={todos}
        subagents={subagents}
        subagentHistories={subagentHistories}
        currentSessionId={currentSessionId}
        currentProvider={currentProvider}
        expanded={statusPanelExpanded}
        isStreaming={streamingActive}
        onToggleStatusPanel={onToggleStatusPanel}
      />

      <div className="input-area" ref={inputAreaRef}>
        <ChatInputBox
          ref={chatInputRef}
          isLoading={loading}
          selectedModel={selectedModel}
          currentProvider={currentProvider}
          status={status}
          placeholder=''
          value={draftInput}
          onInput={setDraftInput}
          onSubmit={handleSubmit}
          onStop={onInterrupt}
          onModelSelect={onModelSelect}
          reasoningEffort={reasoningEffort}
          piThinkingLevels={piThinkingLevels}
          onReasoningChange={onReasoningChange}
          sendShortcut={sendShortcut}
          sendBehaviorMode={sendBehaviorMode}
          addToast={addToast}
          messageQueue={messageQueue}
          onRemoveFromQueue={onRemoveFromQueue}
          onRecallFromQueue={onRecallFromQueue}
        />
      </div>
        </>
      )}
    </div>
  );
};
