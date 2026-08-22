import { useState, useCallback, useRef } from 'react';
import type { Attachment } from '../components/ChatInputBox/types';

export interface QueuedMessage {
  id: string;
  content: string;
  attachments?: Attachment[];
  queuedAt: number;
}

export interface UseMessageQueueOptions {
  /** Callback to execute a message */
  onExecute: (content: string, attachments?: Attachment[]) => void;
}

export interface UseMessageQueueReturn {
  /** Current queue */
  queue: QueuedMessage[];
  /** Add message to queue */
  enqueue: (content: string, attachments?: Attachment[]) => void;
  /** Remove message from queue by id */
  dequeue: (id: string) => void;
  /** Clear entire queue */
  clearQueue: () => void;
  /** Whether queue has items */
  hasQueuedMessages: boolean;
  /** 手动取出并执行下一条（在 agent 回合完成/初始化结束时由调用方触发） */
  drainOne: () => void;
}

/**
 * Hook for managing message queue
 *
 * 不自动监听 isLoading（该状态在工具执行间隙会短暂抖动，不可靠）——
 * 由调用方在明确的"回合完成"信号（Java onAgentCompleted）或初始化结束时调用 drainOne。
 */
export function useMessageQueue({ onExecute }: UseMessageQueueOptions): UseMessageQueueReturn {
  const [queue, setQueue] = useState<QueuedMessage[]>([]);
  const isExecutingFromQueueRef = useRef(false);

  // Generate unique ID
  const generateId = useCallback(() => {
    return `queue-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }, []);

  // Add message to queue
  const enqueue = useCallback((content: string, attachments?: Attachment[]) => {
    const newItem: QueuedMessage = {
      id: generateId(),
      content,
      attachments,
      queuedAt: Date.now(),
    };
    setQueue(prev => [...prev, newItem]);
  }, [generateId]);

  // Remove message from queue
  const dequeue = useCallback((id: string) => {
    setQueue(prev => prev.filter(item => item.id !== id));
  }, []);

  // Clear entire queue
  const clearQueue = useCallback(() => {
    setQueue([]);
  }, []);

  // Manually execute the next queued message (if any). Guarded against re-entry.
  const drainOne = useCallback(() => {
    if (isExecutingFromQueueRef.current) return;
    setQueue(prev => {
      if (prev.length === 0) return prev;
      const [next, ...rest] = prev;
      isExecutingFromQueueRef.current = true;
      // Execute with small delay to ensure state updates settle
      setTimeout(() => {
        onExecute(next.content, next.attachments);
        isExecutingFromQueueRef.current = false;
      }, 50);
      return rest;
    });
  }, [onExecute]);

  return {
    queue,
    enqueue,
    dequeue,
    clearQueue,
    hasQueuedMessages: queue.length > 0,
    drainOne,
  };
}
