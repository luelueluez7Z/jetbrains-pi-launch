import { useCallback } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent, MutableRefObject } from 'react';
import type { SendBehavior, SendBehaviorMode } from '../../../utils/sendBehavior.js';
import { behaviorForEnter, behaviorForTab } from '../../../utils/sendBehavior.js';

interface CompletionWithKeyDown {
  isOpen: boolean;
  handleKeyDown: (ev: KeyboardEvent) => boolean;
}

interface InlineCompletionHandler {
  applySuggestion: () => boolean;
}

export interface UseKeyboardHandlerOptions {
  isComposingRef: MutableRefObject<boolean>;
  lastCompositionEndTimeRef: MutableRefObject<number>;
  sendShortcut: 'enter' | 'cmdEnter';
  fileCompletion: CompletionWithKeyDown;
  commandCompletion: CompletionWithKeyDown;
  agentCompletion: CompletionWithKeyDown;
  dollarCommandCompletion: CompletionWithKeyDown;
  handleMacCursorMovement: (e: ReactKeyboardEvent<HTMLDivElement>) => boolean;
  handleHistoryKeyDown: (e: {
    key: string;
    metaKey?: boolean;
    ctrlKey?: boolean;
    altKey?: boolean;
    shiftKey?: boolean;
    preventDefault: () => void;
    stopPropagation: () => void;
  }) => boolean;
  /** Inline history completion (Tab to apply) */
  inlineCompletion?: InlineCompletionHandler;
  completionSelectedRef: MutableRefObject<boolean>;
  submittedOnEnterRef: MutableRefObject<boolean>;
  /** 流式发送键位模式（决定回车/Tab 分别触发引导还是后续） */
  sendBehaviorMode: SendBehaviorMode;
  handleSubmit: (behavior?: SendBehavior) => void;
}

/**
 * useKeyboardHandler - React keyboard event handling for the chat input box
 *
 * Handles:
 * - Completion dropdown navigation
 * - History navigation (when input empty)
 * - Send shortcut (Enter / Cmd+Enter)
 * - Preventing IME "confirm enter" false send
 */
export function useKeyboardHandler({
  isComposingRef,
  lastCompositionEndTimeRef,
  sendShortcut,
  fileCompletion,
  commandCompletion,
  agentCompletion,
  dollarCommandCompletion,
  handleMacCursorMovement,
  handleHistoryKeyDown,
  inlineCompletion,
  completionSelectedRef,
  submittedOnEnterRef,
  sendBehaviorMode,
  handleSubmit,
}: UseKeyboardHandlerOptions) {
  const onKeyDown = useCallback(
    (e: ReactKeyboardEvent<HTMLDivElement>) => {
      const isIMEComposing = isComposingRef.current || e.nativeEvent.isComposing;

      const isEnterKey =
        e.key === 'Enter' || e.nativeEvent.keyCode === 13;

      if (handleMacCursorMovement(e)) return;

      const isCursorMovementKey =
        e.key === 'Home' ||
        e.key === 'End' ||
        ((e.key === 'a' || e.key === 'A') && e.ctrlKey && !e.metaKey) ||
        ((e.key === 'e' || e.key === 'E') && e.ctrlKey && !e.metaKey);
      if (isCursorMovementKey) return;

      if (fileCompletion.isOpen) {
        const handled = fileCompletion.handleKeyDown(e.nativeEvent);
        if (handled) {
          e.preventDefault();
          e.stopPropagation();
          if (e.key === 'Enter') completionSelectedRef.current = true;
          return;
        }
      }

      if (commandCompletion.isOpen) {
        const handled = commandCompletion.handleKeyDown(e.nativeEvent);
        if (handled) {
          e.preventDefault();
          e.stopPropagation();
          if (e.key === 'Enter') completionSelectedRef.current = true;
          return;
        }
      }

      if (agentCompletion.isOpen) {
        const handled = agentCompletion.handleKeyDown(e.nativeEvent);
        if (handled) {
          e.preventDefault();
          e.stopPropagation();
          if (e.key === 'Enter') completionSelectedRef.current = true;
          return;
        }
      }

      if (dollarCommandCompletion.isOpen) {
        const handled = dollarCommandCompletion.handleKeyDown(e.nativeEvent);
        if (handled) {
          e.preventDefault();
          e.stopPropagation();
          if (e.key === 'Enter') completionSelectedRef.current = true;
          return;
        }
      }

      // Handle inline history completion (Tab key)
      if (e.key === 'Tab' && inlineCompletion) {
        const applied = inlineCompletion.applySuggestion();
        if (applied) {
          e.preventDefault();
          e.stopPropagation();
          return;
        }
      }

      // Tab 发送（按模式分发：steerOnEnter → Tab=后续 followUp；followUpOnEnter → Tab=引导 steer）
      // 仅在无补全下拉/内联建议占用了 Tab 时才发送，避免键位冲突
      if (e.key === 'Tab' && !isIMEComposing) {
        e.preventDefault();
        e.stopPropagation();
        submittedOnEnterRef.current = true;
        handleSubmit(behaviorForTab(sendBehaviorMode));
        return;
      }

      if (handleHistoryKeyDown(e)) return;

      const isRecentlyComposing = Date.now() - lastCompositionEndTimeRef.current < 100;
      const isSendKey =
        sendShortcut === 'cmdEnter'
          ? isEnterKey && (e.metaKey || e.ctrlKey) && !isIMEComposing
          : isEnterKey && !e.shiftKey && !isIMEComposing && !isRecentlyComposing;

      if (!isSendKey) return;

      e.preventDefault();

      submittedOnEnterRef.current = true;
      handleSubmit(behaviorForEnter(sendBehaviorMode));
    },
    [
      isComposingRef,
      handleMacCursorMovement,
      fileCompletion,
      commandCompletion,
      agentCompletion,
      dollarCommandCompletion,
      handleHistoryKeyDown,
      inlineCompletion,
      lastCompositionEndTimeRef,
      sendShortcut,
      submittedOnEnterRef,
      completionSelectedRef,
      sendBehaviorMode,
      handleSubmit,
    ]
  );

  const onKeyUp = useCallback(
    (e: ReactKeyboardEvent<HTMLDivElement>) => {
      const isEnterKey =
        e.key === 'Enter' || e.nativeEvent.keyCode === 13;

      const isSendKey =
        sendShortcut === 'cmdEnter'
          ? isEnterKey && (e.metaKey || e.ctrlKey)
          : isEnterKey && !e.shiftKey;

      if (!isSendKey) return;
      e.preventDefault();

      if (completionSelectedRef.current) {
        completionSelectedRef.current = false;
        return;
      }
      if (submittedOnEnterRef.current) {
        submittedOnEnterRef.current = false;
      }
    },
    [sendShortcut, completionSelectedRef, submittedOnEnterRef]
  );

  return { onKeyDown, onKeyUp };
}
