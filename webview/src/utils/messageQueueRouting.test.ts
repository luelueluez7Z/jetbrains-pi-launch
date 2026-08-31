import { describe, expect, it } from 'vitest';
import { resolveMessageQueueRoute } from './messageQueueRouting';

describe('resolveMessageQueueRoute', () => {
  it('初始化 loading 间隙的默认 steer 也应直接发送', () => {
    expect(resolveMessageQueueRoute({
      loading: true,
      streamingActive: false,
      streamingSeen: false,
      behavior: 'steer',
    })).toBe('send');
  });

  it('流式期间 followUp 进入本地队列', () => {
    expect(resolveMessageQueueRoute({
      loading: true,
      streamingActive: true,
      streamingSeen: true,
      behavior: 'followUp',
    })).toBe('followUpQueue');
  });

  it('初始化 loading 阶段暂存 followUp', () => {
    expect(resolveMessageQueueRoute({
      loading: true,
      streamingActive: false,
      streamingSeen: false,
      behavior: 'followUp',
    })).toBe('initialQueue');
  });

  it('已有流式回合后 loading 抖动不应重新进入初始化队列', () => {
    expect(resolveMessageQueueRoute({
      loading: true,
      streamingActive: false,
      streamingSeen: true,
      behavior: 'steer',
    })).toBe('send');
  });
});
