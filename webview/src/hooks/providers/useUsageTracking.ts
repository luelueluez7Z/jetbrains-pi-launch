import { useState } from 'react';

/**
 * Usage % / token counters（后端 onUsageUpdate 推送，仅 pi）。
 * SDK 状态（claude-sdk / codex-sdk）是 cc-gui 概念，pi 无 npm SDK，已移除。
 */
export function useUsageTracking() {
  const [usagePercentage, setUsagePercentage] = useState(0);
  const [usageUsedTokens, setUsageUsedTokens] = useState<number | undefined>(undefined);
  const [usageMaxTokens, setUsageMaxTokens] = useState<number | undefined>(undefined);

  return {
    usagePercentage,
    setUsagePercentage,
    usageUsedTokens,
    setUsageUsedTokens,
    usageMaxTokens,
    setUsageMaxTokens,
  };
}

export type UseUsageTrackingReturn = ReturnType<typeof useUsageTracking>;
