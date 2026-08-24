import type { ModelInfo } from './types';

export interface ResolveProviderModelsInput {
  provider: string;
  /** Dynamic catalog from useCliModels (backend push；pi 模式唯一来源). */
  cliModels: ModelInfo[];
}

/**
 * 模型下拉列表来源（纯 pi）：只认后端推送的 cliModels。
 * claude/codex/grok/kimi 等 cc-gui 遗留目录已移除。
 */
export function resolveProviderModels({
  provider,
  cliModels,
}: ResolveProviderModelsInput): ModelInfo[] {
  if (provider !== 'pi') return [];
  return cliModels;
}
