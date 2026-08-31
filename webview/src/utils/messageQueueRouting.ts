import type { SendBehavior } from './sendBehavior.js';

/**
 * 提交消息时的前端分流结果。
 *
 * 初始化期间暂存 followUp，流式回合中的 followUp 进入本地队列；
 * steer 始终直接交给发送器，由后端按 Pi 的 streamingBehavior 语义处理。
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
 * 只有明确处于流式回合时，followUp 才暂存在本地队列。
 */
export function resolveMessageQueueRoute({
  loading,
  streamingActive,
  streamingSeen,
  behavior,
}: MessageQueueRoutingInput): MessageQueueRoute {
  // 引导消息不能被前端 loading 状态拦截。loading 在 agent_start/stream_start
  // 之间存在竞态，若在这里入本地队列，就会错误地等到整个回合结束才发送。
  if (behavior === 'steer') return 'send';

  if (streamingActive && behavior === 'followUp') {
    return 'followUpQueue';
  }
  if (loading && !streamingActive && !streamingSeen) {
    return 'initialQueue';
  }
  return 'send';
}
