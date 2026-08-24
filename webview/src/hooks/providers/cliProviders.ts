import type { PermissionMode } from '../../components/ChatInputBox/types';

/** 纯 pi：唯一的 provider。保留兼容导出（调用方清理中）。 */
export const CLI_ONLY_PROVIDERS = new Set(['pi']);

export function isCliOnlyProvider(providerId: string | null | undefined): boolean {
  return providerId === 'pi';
}

/** Plan mode is not exposed for CLI providers（pi 的 plan 走 plan-mode 扩展，非 permissionMode）。 */
export function normalizeCliPermissionMode(mode: PermissionMode): PermissionMode {
  return mode === 'plan' ? 'default' : mode;
}
