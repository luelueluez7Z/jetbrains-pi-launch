import type { SendBehavior } from './sendBehavior.js';

/**
 * 提交消息时的前端分流结果。
 *
 * 初始化期间暂存消息，流式回合中的 followUp 进入本地队列；
 * 其余情况直接交给发送器，由后端按 steer/followUp 语义转发给 pi。
 */
export type MessageQueueRoute = 'initialQueue' | 'followUpQueue' | 'send';

export interface MessageQueueRoutingInput {
  loading: boolean;
  streamingActive: boolean;
  streamingSeen: boolean;
  behavior: SendBehavior;
}

/**
 * 决定消息是否应进入本地队列。
 *
 * loading 在流式回合中也可能为 true，因此不能单独用 loading 拦截 steer；
 * streamingActive 是 steer 是否应立即发送的权威状态。
 */
export function resolveMessageQueueRoute({
  loading,
  streamingActive,
  streamingSeen,
  behavior,
}: MessageQueueRoutingInput): MessageQueueRoute {
  if (streamingActive && behavior === 'followUp') {
    return 'followUpQueue';
  }
  if (loading && !streamingActive && !streamingSeen) {
    return 'initialQueue';
  }
  return 'send';
}
