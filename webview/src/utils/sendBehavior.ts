/**
 * 流式发送行为（模型对话进行中发消息）的键位配置。
 *
 * pi 支持两种发送语义：
 * - steer：引导当前回合，在 Pi 的工具回合边界注入新指令
 * - followUp：不打断，排队等待当前对话完成后按序执行
 *
 * 用户可在设置页配置回车/Tab 分别对应哪种语义：
 * - 'steerOnEnter'（默认，用户习惯）：回车 = 引导（steer），Tab = 后续（followUp）
 * - 'followUpOnEnter'：回车 = 后续（followUp），Tab = 引导（steer）
 *
 * 纯本地 UI 偏好，存 localStorage（符合"前端不持有权威数据"约定）。
 */

export type SendBehavior = 'steer' | 'followUp';

export type SendBehaviorMode = 'steerOnEnter' | 'followUpOnEnter';

export const SEND_BEHAVIOR_MODE_KEY = 'sendBehaviorMode';

/** 默认使用用户习惯：回车引导、Tab 后续 */
export const DEFAULT_SEND_BEHAVIOR_MODE: SendBehaviorMode = 'steerOnEnter';

export function readSendBehaviorMode(): SendBehaviorMode {
  try {
    const saved = localStorage.getItem(SEND_BEHAVIOR_MODE_KEY);
    if (saved === 'steerOnEnter' || saved === 'followUpOnEnter') return saved;
  } catch {
    // localStorage 不可用时回退默认
  }
  return DEFAULT_SEND_BEHAVIOR_MODE;
}

export function writeSendBehaviorMode(mode: SendBehaviorMode): void {
  try {
    localStorage.setItem(SEND_BEHAVIOR_MODE_KEY, mode);
  } catch {
    // 忽略存储失败
  }
}

/** 回车键对应的发送行为 */
export function behaviorForEnter(mode: SendBehaviorMode): SendBehavior {
  return mode === 'steerOnEnter' ? 'steer' : 'followUp';
}

/** Tab 键对应的发送行为 */
export function behaviorForTab(mode: SendBehaviorMode): SendBehavior {
  return mode === 'steerOnEnter' ? 'followUp' : 'steer';
}
