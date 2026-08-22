import type { QueuedMessage } from '../../hooks/useMessageQueue';

export interface MessageQueueProps {
  /** Queue items */
  queue: QueuedMessage[];
  /** Remove item callback */
  onRemove: (id: string) => void;
  /** 撤回：把排队消息内容写回输入框并移出队列 */
  onRecall: (id: string) => void;
}

/**
 * MessageQueue - Displays queued messages above input box
 * Shows numbered list with message preview and close button
 */
export function MessageQueue({ queue, onRemove, onRecall }: MessageQueueProps) {
  if (queue.length === 0) {
    return null;
  }

  return (
    <div className="message-queue">
      {/* Render in reverse order so newest is at bottom (closest to input) */}
      {[...queue].reverse().map((item, reversedIndex) => {
        // Calculate actual queue position (1-based, from bottom)
        const queuePosition = queue.length - reversedIndex;
        return (
          <div key={item.id} className="message-queue-item">
            <span className="message-queue-number">{queuePosition}</span>
            <span className="message-queue-content" title={item.content}>
              {item.content}
            </span>
            <button
              className="message-queue-recall"
              onClick={() => onRecall(item.id)}
              title="撤回消息到输入框"
            >
              <span className="codicon codicon-undo" />
            </button>
            <button
              className="message-queue-remove"
              onClick={() => onRemove(item.id)}
              title="Remove from queue"
            >
              <span className="codicon codicon-close" />
            </button>
          </div>
        );
      })}
    </div>
  );
}

export default MessageQueue;
