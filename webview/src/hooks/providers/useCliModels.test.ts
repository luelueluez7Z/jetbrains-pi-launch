import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { __resetCliModelsCacheForTests, useCliModels } from './useCliModels';

const sendBridgeEventMock = vi.hoisted(() => vi.fn());

vi.mock('../../utils/bridge', () => ({
  sendBridgeEvent: (...args: unknown[]) => sendBridgeEventMock(...args),
}));

function emitCliModels(payload: unknown) {
  act(() => {
    window.setCliModels?.(JSON.stringify(payload));
  });
}

describe('useCliModels（纯 pi）', () => {
  beforeEach(() => {
    sendBridgeEventMock.mockClear();
    __resetCliModelsCacheForTests();
  });

  afterEach(() => {
    delete window.setCliModels;
    __resetCliModelsCacheForTests();
    vi.useRealTimers();
  });

  it('fetches the pi catalog when pi is active', () => {
    renderHook(() => useCliModels('pi'));
    expect(sendBridgeEventMock).toHaveBeenCalledWith('get_cli_models', 'pi');
  });

  it('does not fetch for non-pi providers', () => {
    renderHook(() => useCliModels('claude'));
    expect(sendBridgeEventMock).not.toHaveBeenCalled();
  });

  it('starts with an empty list before the catalog arrives', () => {
    const { result } = renderHook(() => useCliModels('pi'));
    expect(result.current.cliModels).toEqual([]);
    expect(result.current.cliModelsLoading).toBe(true);
  });

  it('stores the pi catalog and defaultModel from the backend payload', () => {
    const { result } = renderHook(() => useCliModels('pi'));
    emitCliModels({
      success: true,
      provider: 'pi',
      defaultModel: 'deepseek-v4-flash',
      models: [{ id: 'deepseek::deepseek-v4-flash', label: 'DeepSeek V4 Flash' }],
    });
    expect(result.current.cliModels).toEqual([
      { id: 'deepseek::deepseek-v4-flash', label: 'DeepSeek V4 Flash' },
    ]);
    expect(result.current.cliDefaultModel).toBe('deepseek-v4-flash');
    expect(result.current.cliModelsLoading).toBe(false);
    expect(result.current.cliModelsError).toBeNull();
  });

  it('records backend errors and supports manual retry', () => {
    const { result } = renderHook(() => useCliModels('pi'));
    // 传非空 models 避免 effect 自动重试清除 error；后端失败时通常保留旧目录
    emitCliModels({
      success: false,
      provider: 'pi',
      error: 'node missing',
      models: [{ id: 'deepseek::deepseek-v4-flash', label: 'DeepSeek' }],
    });
    expect(result.current.cliModelsError).toBe('node missing');

    sendBridgeEventMock.mockClear();
    act(() => {
      result.current.refreshCliModels('pi');
    });
    expect(sendBridgeEventMock).toHaveBeenCalledWith('get_cli_models', 'pi');
  });

  it('times out into an error state', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useCliModels('pi'));
    act(() => {
      vi.advanceTimersByTime(16_000);
    });
    expect(result.current.cliModelsLoading).toBe(false);
    expect(result.current.cliModelsError).toBe('timeout');
    expect(result.current.cliModels).toEqual([]);
  });

  it('reuses the module cache on remount so history→chat does not re-fetch', () => {
    const first = renderHook(() => useCliModels('pi'));
    emitCliModels({
      success: true,
      provider: 'pi',
      defaultModel: 'deepseek-v4-flash',
      models: [{ id: 'deepseek::deepseek-v4-flash', label: 'DeepSeek V4 Flash' }],
    });
    expect(first.result.current.cliModels.map((m) => m.id)).toEqual([
      'deepseek::deepseek-v4-flash',
    ]);
    first.unmount();

    sendBridgeEventMock.mockClear();
    const second = renderHook(() => useCliModels('pi'));
    // Cache already has entries — no bridge round-trip on remount.
    expect(sendBridgeEventMock).not.toHaveBeenCalled();
    expect(second.result.current.cliModels.map((m) => m.id)).toEqual([
      'deepseek::deepseek-v4-flash',
    ]);
    expect(second.result.current.cliCatalogHasEntries).toBe(true);
    expect(second.result.current.cliModelsLoading).toBe(false);
  });
});
