import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryView from './components/history/HistoryView';
import SettingsView from './components/settings';
import { sendBridgeEvent } from './utils/bridge';
import { preloadSlashCommands } from './components/ChatInputBox/providers';
import {
  useScrollBehavior,
  useSessionManagement,
  useStreamingMessages,
  useWindowCallbacks,
  useHistoryLoader,
  useMessageQueue,
  useThemeInit,
  useContextActions,
  useMessageProcessing,
  useMessageSender,
  useModelProviderState,
  useChatComputations,
} from './hooks';
import {
  NEW_SESSION_COMMANDS,
  RESUME_COMMANDS,
} from './hooks/useMessageSender';
import { applyDiffTheme, getStoredDiffTheme } from './utils/diffTheme';
import {
  readSendBehaviorMode,
  writeSendBehaviorMode,
  type SendBehavior,
  type SendBehaviorMode,
} from './utils/sendBehavior';
import { resolveMessageQueueRoute } from './utils/messageQueueRouting';
import { collectTaskEventsFromMessages } from './utils/taskNotificationMessage';
import type { ClaudeMessage } from './types';
import type { Attachment, ChatInputBoxHandle } from './components/ChatInputBox/types';
import { ToastContainer } from './components/Toast';
import ErrorBoundary from './components/ErrorBoundary';
import { ChatHeader } from './components/ChatHeader';
import { ChatScreen } from './components/ChatScreen';
import { useSubagentContextValues, useSetTaskEvents } from './contexts/SubagentContext';
import { useMessages } from './contexts/MessagesContext';
import { useSession } from './contexts/SessionContext';
import { useUIState } from './contexts/UIStateContext';
import { useDialogs } from './contexts/DialogContext';
import { AppDialogs } from './components/AppDialogs';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from './utils/permissionDialogTimeout';

const App = () => {
  const { t } = useTranslation();

  // ── Dialog management (extracted to DialogContext, stage 4 of TASK-P1-01) ──
  // Open* / set* are still needed by hooks (useWindowCallbacks, useRewindHandlers).
  // Display state (permissionDialogOpen / askUserQuestionDialogOpen / etc.) is
  // consumed directly inside <AppDialogs> via useDialogs().
  const {
    openPermissionDialog,
    openAskUserQuestionDialog,
    forceClosePermissionDialog,
    forceCloseAskUserQuestionDialog,
  } = useDialogs();

  // ── Messages flow state (extracted to MessagesContext, stage 1 of TASK-P1-01) ──
  // Display state (loadingStartTime / isThinking) is consumed inside <ChatScreen>.
  const {
    messages, setMessages,
    subagentHistories, setSubagentHistories,
    setStatus,
    loading, setLoading, setLoadingStartTime,
    setIsThinking,
    streamingActive, setStreamingActive,
  } = useMessages();

  // task_events live in TaskEventProvider (SubagentContext) so their updates do
  // not re-render every MessagesContext consumer.
  const setTaskEvents = useSetTaskEvents();

  // ── Session state (extracted to SessionContext, stage 2 of TASK-P1-01) ──
  const {
    currentSessionId, setCurrentSessionId,
    customSessionTitle, setCustomSessionTitle,
    historyData, setHistoryData,
    currentSessionIdRef, customSessionTitleRef,
  } = useSession();

  // ── UI state (extracted to UIStateContext, stage 3 of TASK-P1-01) ──
  // Dialog visibility (addModelDialog / changelog) is consumed inside AppDialogs.
  const {
    currentView, setCurrentView,
    settingsInitialTab, setSettingsInitialTab,
    toasts, addToast, dismissToast, clearToasts,
    setContextInfo,
  } = useUIState();

  // ── Permission dialog timeout (synced with backend config) ──
  const [permissionDialogTimeoutSeconds, setPermissionDialogTimeoutSeconds] = useState(DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS);

  // ── StatusPanel (todos + subagents) expand/collapse state ──
  const [statusPanelExpanded, setStatusPanelExpanded] = useState(true);

  // ── Local refs (don't trigger re-render, kept in App.tsx) ──
  const isFirstMountRef = useRef(true);
  const chatInputRef = useRef<ChatInputBoxHandle>(null);

  // ── Theme & context actions ──
  useThemeInit();
  useContextActions();

  // Apply diff theme on app startup so diff styles work before opening Settings.
  useEffect(() => {
    const ideTheme = window.__INITIAL_IDE_THEME__ ?? null;
    applyDiffTheme(getStoredDiffTheme(), ideTheme);
  }, []);

  // ── Scroll behavior ──
  const {
    messagesContainerRef, messagesEndRef, inputAreaRef,
    isUserAtBottomRef, userPausedRef,
  } = useScrollBehavior({ currentView, messages, loading, streamingActive });

  // ── Streaming messages ──
  const {
    streamingContentRef, streamingThinkingRef, isStreamingRef, useBackendStreamingRenderRef,
    streamingMessageIndexRef, contentUpdateTimeoutRef, thinkingUpdateTimeoutRef,
    lastContentUpdateRef, lastThinkingUpdateRef, autoExpandedThinkingKeysRef,
    streamingTurnIdRef, turnIdCounterRef,
    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
  } = useStreamingMessages();

  // (Toast helpers moved to UIStateContext)

  // ── Model/Provider state（纯 pi：currentProvider 固定 'pi'）──
  const {
    currentProvider, selectedModel,
    currentProviderRef,
    reasoningEffort, piThinkingLevels, sendShortcut,
    setPermissionMode,
    setSelectedPiModel,
    setReasoningEffort, setPiThinkingLevels,
    setStreamingEnabledSetting, setSendShortcut, setAutoOpenFileEnabled,
    setSelectedAgent,
    setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    handleModelSelect, handleReasoningChange,
  } = useModelProviderState({ addToast, t });

  // ── Global drag event interception ──
  useEffect(() => {
    const preventExternalDrop = (e: DragEvent) => {
      const types = Array.from(e.dataTransfer?.types ?? []);
      const isExternalDrop = types.includes('Files') || types.includes('text/uri-list');
      if (!isExternalDrop) return;
      e.preventDefault();
      e.stopPropagation();
    };
    document.addEventListener('dragover', preventExternalDrop);
    document.addEventListener('drop', preventExternalDrop);
    document.addEventListener('dragenter', preventExternalDrop);
    return () => {
      document.removeEventListener('dragover', preventExternalDrop);
      document.removeEventListener('drop', preventExternalDrop);
      document.removeEventListener('dragenter', preventExternalDrop);
    };
  }, []);

  // Suppress JCEF's native context menu for every Webview display area. Use
  // bubbling so React child handlers can open custom menus before the fallback
  // suppression runs; do not stop propagation here.
  useEffect(() => {
    const preventNativeContextMenu = (event: MouseEvent) => {
      event.preventDefault();
    };
    document.addEventListener('contextmenu', preventNativeContextMenu);
    return () => {
      document.removeEventListener('contextmenu', preventNativeContextMenu);
    };
  }, []);

  // ── Close in-conversation search panel when navigating away from chat ──
  // ── Slash command preloading ──
  useEffect(() => {
    preloadSlashCommands();
  }, []);

  useEffect(() => {
    if (isFirstMountRef.current) { isFirstMountRef.current = false; return; }
    if (currentView === 'chat') { /* chat view entered */ }
  }, [currentView]);

  // Recover task events from task-notification user messages. Recent Claude Code
  // delivers a background agent's terminal report as a plain user message (XML
  // in content) instead of an SDK task_notification event, so history replay —
  // and any live session that never fired the SDK path — would otherwise leave
  // the subagent card stuck on the launch ack text. Derived entries only fill
  // gaps: a real SDK event already in the map is kept as-is.
  // Messages update immutably, so unchanged messages keep their object identity;
  // tracking scanned objects avoids re-scanning the whole conversation on every
  // streaming chunk.
  const scannedTaskNotificationMessagesRef = useRef(new WeakSet<ClaudeMessage>());
  useEffect(() => {
    const scanned = scannedTaskNotificationMessagesRef.current;
    const fresh = messages.filter((m) => !scanned.has(m));
    if (fresh.length === 0) return;
    for (const m of fresh) scanned.add(m);
    const derived = collectTaskEventsFromMessages(fresh);
    if (Object.keys(derived).length === 0) return;
    setTaskEvents((prev) => {
      let changed = false;
      const next = { ...prev };
      for (const [id, event] of Object.entries(derived)) {
        if (next[id]) continue;
        next[id] = event;
        changed = true;
      }
      return changed ? next : prev;
    });
  }, [messages, setTaskEvents]);

  // ── Session management ──
  const {
    showNewSessionConfirm, showInterruptConfirm,
    suppressNextStatusToastRef,
    createNewSession, forceCreateNewSession,
    handleConfirmNewSession, handleCancelNewSession,
    handleConfirmInterrupt, handleCancelInterrupt,
    loadHistorySession, deleteHistorySession, deleteHistorySessions,
    updateHistoryTitle, applyHistoryTitleLocal,
  } = useSessionManagement({
    messages, loading, historyData, currentSessionId, currentSessionIdRef, currentProvider,
    setHistoryData, setMessages, setCurrentView, setCurrentSessionId,
    setCustomSessionTitle, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    setStatus, setLoading, setIsThinking, setStreamingActive,
    setTaskEvents,
    setSubagentHistories,
    clearToasts, addToast, t,
    applyHistoryModel: (_provider, model, _agent) => {
      // 插件只接 pi：provider/agent 忽略（历史会话均来自 pi），仅应用模型
      if (model) {
        setSelectedPiModel(model);
        sendBridgeEvent('set_model', model);
      }
    },
  });

  useHistoryLoader({ currentView, currentProvider });

  // ── Window callbacks (bridge communication) ──
  useWindowCallbacks({
    t, addToast, clearToasts,
    setMessages, setStatus, setLoading, setLoadingStartTime,
    setIsThinking, setStreamingActive, setHistoryData,
    setCurrentSessionId, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    setPermissionMode, setSelectedPiModel,
    setReasoningEffort, setPiThinkingLevels,
    setStreamingEnabledSetting,
    setSendShortcut, setAutoOpenFileEnabled,
    setContextInfo, setSelectedAgent,
    setSubagentHistories,
    setTaskEvents,
    currentProviderRef, messagesContainerRef, isUserAtBottomRef, userPausedRef,
    suppressNextStatusToastRef,
    streamingContentRef, streamingThinkingRef, isStreamingRef, useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingMessageIndexRef,
    streamingTurnIdRef, turnIdCounterRef,
    lastContentUpdateRef, contentUpdateTimeoutRef,
    lastThinkingUpdateRef, thinkingUpdateTimeoutRef,
    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
    openPermissionDialog, openAskUserQuestionDialog,
    forceClosePermissionDialog, forceCloseAskUserQuestionDialog,
    customSessionTitleRef, currentSessionIdRef, updateHistoryTitle, applyHistoryTitleLocal,
    setCustomSessionTitle,
    onAgentCompleted: () => drainFollowUpQueueRef.current(),
    setPermissionDialogTimeoutSeconds,
  });

  // ── Message processing ──
  const {
    getMessageText, getContentBlocks,
    mergedMessages, sentAttachmentsRef,
  } = useMessageProcessing({ messages, currentSessionId, t });

  // ── Message sender ──
  const {
    handleSubmit: hookHandleSubmit,
    executeMessage,
    interruptSession,
  } = useMessageSender({
    t, addToast,
    currentProvider, selectedModel, reasoningEffort,
    sentAttachmentsRef, chatInputRef, messagesContainerRef,
    isUserAtBottomRef, userPausedRef, isStreamingRef,
    setMessages, setLoading, setLoadingStartTime, setStreamingActive,
    setCurrentView,
    forceCreateNewSession,
  });

  // ── Message queue ──
  // 不再自动监听 isLoading（工具执行间隙会抖动），改为在 agent 回合完成
  // （Java onAgentCompleted）或初始化 loading 完成时由调用方手动 drainOne。
  const {
    queue: messageQueue,
    enqueue: enqueueMessage,
    dequeue: dequeueMessage,
    recall: recallMessage,
    drainOne: drainFollowUpQueue,
  } = useMessageQueue({ onExecute: executeMessage });

  // 撤回排队消息到输入框（保留原有内容，追加到末尾）
  const handleRecallFromQueue = useCallback((id: string) => {
    const item = recallMessage(id);
    if (!item) return;
    const input = chatInputRef.current;
    if (input) {
      const current = input.getValue();
      input.setValue(current.trim() ? `${current}\n${item.content}` : item.content);
      input.focus();
    }
  }, [recallMessage]);

  // 供 useWindowCallbacks（声明在 useMessageQueue 之前）通过 ref 访问 drain 方法
  const drainFollowUpQueueRef = useRef<() => void>(() => {});
  drainFollowUpQueueRef.current = drainFollowUpQueue;

  // 纯初始化 loading 完成（从未进入过流式）时 drain 队列；
  // agent 场景由 onAgentCompleted 事件驱动，避免工具执行间隙 loading 抖动误触发
  const streamingSeenRef = useRef(false);
  useEffect(() => {
    if (streamingActive) streamingSeenRef.current = true;
  }, [streamingActive]);
  // 会话切换后重新进入初始化阶段，不能沿用上一会话的流式观测结果。
  const streamingSeenSessionRef = useRef(currentSessionId);
  useEffect(() => {
    if (streamingSeenSessionRef.current === currentSessionId) return;
    streamingSeenSessionRef.current = currentSessionId;
    streamingSeenRef.current = false;
  }, [currentSessionId]);
  const prevLoadingRef = useRef(loading);
  useEffect(() => {
    const wasLoading = prevLoadingRef.current;
    prevLoadingRef.current = loading;
    if (wasLoading && !loading && !streamingActive && !streamingSeenRef.current) {
      drainFollowUpQueueRef.current();
    }
  }, [loading, streamingActive]);

  // ── 流式发送键位模式（回车/Tab 语义：引导 steer / 后续 followUp），存 localStorage ──
  const [sendBehaviorMode, setSendBehaviorMode] = useState<SendBehaviorMode>(() => readSendBehaviorMode());
  const handleSendBehaviorModeChange = useCallback((mode: SendBehaviorMode) => {
    setSendBehaviorMode(mode);
    writeSendBehaviorMode(mode);
  }, []);

  // handleSubmit with queue support (new session and local commands bypass loading check)
  const handleSubmit = useCallback((content: string, attachments?: Attachment[], behavior: SendBehavior = 'steer') => {
    const text = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
    const hasAttachments = Array.isArray(attachments) && attachments.length > 0;
    if (!text && !hasAttachments) return;
    // Local commands work even while loading
    if (text.startsWith('/')) {
      const command = text.split(/\s+/)[0].toLowerCase();
      // New session commands
      if (NEW_SESSION_COMMANDS.has(command)) {
        forceCreateNewSession();
        return;
      }
      // /resume - open history view
      if (RESUME_COMMANDS.has(command)) {
        setCurrentView('history');
        return;
      }
      // /plan：作为普通命令发送给 pi（pi-plan-mode 扩展处理），不前端拦截
      // /context - handled locally even while loading
    }
    const route = resolveMessageQueueRoute({
      loading,
      streamingActive,
      streamingSeen: streamingSeenRef.current,
      behavior,
    });
    // 初始化尚未完成时暂存消息，等待初始化结束后执行
    if (route === 'initialQueue') {
      enqueueMessage(content, attachments);
      return;
    }
    // 模型对话进行中 + followUp：进本地队列排队，等当前对话完成后自动执行
    if (route === 'followUpQueue') {
      enqueueMessage(content, attachments);
      return;
    }
    // steer 始终直接发送；后端会把 streamingBehavior 交给 Pi，避免前端
    // loading/streamingActive 状态与 RPC 进程状态之间的竞态。
    hookHandleSubmit(content, attachments, behavior);
  }, [loading, streamingActive, enqueueMessage, hookHandleSubmit, forceCreateNewSession, currentProvider, setCurrentView, addToast, t]);

  // ── Chat-view computations (stage 5 of TASK-P1-01) ──
  const {
    findToolResult, getToolResultRaw, sessionTitle,
    subagents, globalTodos,
  } = useChatComputations({
    t, messages, subagentHistories, customSessionTitle, streamingActive,
    getMessageText, getContentBlocks,
  });

  // Stabilize context value references for SubagentContext consumers.
  const { subagentHistoryCtxValue, sessionIdCtxValue } = useSubagentContextValues(
    subagentHistories,
    currentSessionId,
    currentProvider,
  );

  // ── Render ──
  return (
    <>
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
      <ChatHeader
        currentView={currentView}
        sessionTitle={sessionTitle}
        t={t}
        onBack={() => setCurrentView('chat')}
        onNewSession={createNewSession}
        onHistory={() => setCurrentView('history')}
        onSettings={() => {
          setSettingsInitialTab(undefined);
          setCurrentView('settings');
        }}
        titleEditable
        onTitleChange={(newTitle) => {
          setCustomSessionTitle(newTitle);
          if (currentSessionId) {
            updateHistoryTitle(currentSessionId, newTitle);
          }
        }}
      />

      {currentView === 'settings' ? (
        <ErrorBoundary>
          <SettingsView
            onClose={() => setCurrentView('chat')}
            initialTab={settingsInitialTab}
            onSendBehaviorModeChange={handleSendBehaviorModeChange}
          />
        </ErrorBoundary>
      ) : (
        <>
          {/* Keep ChatScreen mounted while browsing history so model catalog,
              scroll position, and draft attachments survive history ↔ chat. */}
          <div
            style={currentView === 'chat'
              ? { display: 'flex', flex: 1, minHeight: 0, flexDirection: 'column', overflow: 'hidden' }
              : { display: 'none' }}
          >
            <ErrorBoundary>
              <ChatScreen
              mergedMessages={mergedMessages}
              getMessageText={getMessageText}
              getContentBlocks={getContentBlocks}
              findToolResult={findToolResult}
              getToolResultRaw={getToolResultRaw}
              subagentHistoryCtxValue={subagentHistoryCtxValue}
              sessionIdCtxValue={sessionIdCtxValue}
              chatInputRef={chatInputRef}
              messagesContainerRef={messagesContainerRef}
              messagesEndRef={messagesEndRef}
              inputAreaRef={inputAreaRef}
              onSubmit={handleSubmit}
              onInterrupt={interruptSession}
              sendBehaviorMode={sendBehaviorMode}
              currentProvider={currentProvider}
              selectedModel={selectedModel}
              reasoningEffort={reasoningEffort}
              piThinkingLevels={piThinkingLevels}
              sendShortcut={sendShortcut}
              onModelSelect={handleModelSelect}
              onReasoningChange={handleReasoningChange}
              messageQueue={messageQueue}
               onRemoveFromQueue={dequeueMessage}
               onRecallFromQueue={handleRecallFromQueue}
              todos={globalTodos}
              subagents={subagents}
              statusPanelExpanded={statusPanelExpanded}
              onToggleStatusPanel={() => setStatusPanelExpanded((prev) => !prev)}
            />
            </ErrorBoundary>
          </div>
          {currentView === 'history' && (
            <ErrorBoundary>
              <HistoryView
                historyData={historyData}
                currentProvider={currentProvider}
                onLoadSession={loadHistorySession}
                onDeleteSession={deleteHistorySession}
                onDeleteSessions={deleteHistorySessions}
          onUpdateTitle={updateHistoryTitle}
        />
            </ErrorBoundary>
          )}
        </>
      )}

      <div id="image-preview-root" />

      <AppDialogs
        showNewSessionConfirm={showNewSessionConfirm}
        onConfirmNewSession={handleConfirmNewSession}
        onCancelNewSession={handleCancelNewSession}
        showInterruptConfirm={showInterruptConfirm}
        onConfirmInterrupt={handleConfirmInterrupt}
        onCancelInterrupt={handleCancelInterrupt}
        permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
      />
    </>
  );
};

export default App;
