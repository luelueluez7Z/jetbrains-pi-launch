import type { Attachment, QueuedMessage } from './types.js';

import { AttachmentList } from './AttachmentList.js';
import { MessageQueue } from './MessageQueue.js';

/**
 * ChatInputBoxHeader - 输入框上方区域（纯 pi）
 * 仅渲染附件列表与消息队列；cc-gui 的 ContextBar（Claude/Codex 上下文栏）已移除。
 */
export function ChatInputBoxHeader({
  attachments,
  onRemoveAttachment,
  messageQueue,
  onRemoveFromQueue,
  onRecallFromQueue,
}: {
  attachments: Attachment[];
  onRemoveAttachment: (id: string) => void;
  messageQueue?: QueuedMessage[];
  onRemoveFromQueue?: (id: string) => void;
  onRecallFromQueue?: (id: string) => void;
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
    </>
  );
}
