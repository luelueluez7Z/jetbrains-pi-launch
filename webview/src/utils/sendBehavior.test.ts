import { describe, it, expect, beforeEach } from 'vitest';
import {
  SEND_BEHAVIOR_MODE_KEY,
  DEFAULT_SEND_BEHAVIOR_MODE,
  readSendBehaviorMode,
  writeSendBehaviorMode,
  behaviorForEnter,
  behaviorForTab,
} from './sendBehavior';

describe('sendBehavior (流式发送键位配置)', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('默认使用用户习惯：steerOnEnter（回车引导 / Tab 后续）', () => {
    expect(readSendBehaviorMode()).toBe('steerOnEnter');
    expect(DEFAULT_SEND_BEHAVIOR_MODE).toBe('steerOnEnter');
  });

  it('读取已存储的模式', () => {
    writeSendBehaviorMode('followUpOnEnter');
    expect(readSendBehaviorMode()).toBe('followUpOnEnter');
  });

  it('忽略非法存储值并回退默认', () => {
    localStorage.setItem(SEND_BEHAVIOR_MODE_KEY, 'bogus');
    expect(readSendBehaviorMode()).toBe(DEFAULT_SEND_BEHAVIOR_MODE);
  });

  it('writeSendBehaviorMode 持久化到 localStorage', () => {
    writeSendBehaviorMode('followUpOnEnter');
    expect(localStorage.getItem(SEND_BEHAVIOR_MODE_KEY)).toBe('followUpOnEnter');
  });

  it('steerOnEnter：回车 = 引导 steer，Tab = 后续 followUp', () => {
    expect(behaviorForEnter('steerOnEnter')).toBe('steer');
    expect(behaviorForTab('steerOnEnter')).toBe('followUp');
  });

  it('followUpOnEnter：回车 = 后续 followUp，Tab = 引导 steer', () => {
    expect(behaviorForEnter('followUpOnEnter')).toBe('followUp');
    expect(behaviorForTab('followUpOnEnter')).toBe('steer');
  });

  it('两种模式的 enter/tab 行为互补（互斥）', () => {
    expect(behaviorForEnter('steerOnEnter')).not.toBe(behaviorForEnter('followUpOnEnter'));
    expect(behaviorForTab('steerOnEnter')).toBe(behaviorForEnter('followUpOnEnter'));
    expect(behaviorForEnter('steerOnEnter')).toBe(behaviorForTab('followUpOnEnter'));
  });
});
