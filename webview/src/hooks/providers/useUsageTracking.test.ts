import { act, renderHook } from '@testing-library/react';
import { useUsageTracking } from './useUsageTracking';

describe('useUsageTracking', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('exposes usage setters and defaults to zero', () => {
    const { result } = renderHook(() => useUsageTracking());

    expect(result.current.usagePercentage).toBe(0);
    expect(result.current.usageUsedTokens).toBeUndefined();
    expect(result.current.usageMaxTokens).toBeUndefined();

    act(() => {
      result.current.setUsagePercentage(42);
      result.current.setUsageUsedTokens(49300);
      result.current.setUsageMaxTokens(258400);
    });

    expect(result.current.usagePercentage).toBe(42);
    expect(result.current.usageUsedTokens).toBe(49300);
    expect(result.current.usageMaxTokens).toBe(258400);
  });
});
