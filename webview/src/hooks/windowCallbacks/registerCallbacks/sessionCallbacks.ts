/**
 * sessionCallbacks.ts
 *
 * Registers window bridge callbacks for session management and SDK dependency
 * status: setSessionId, addToast, onExportSessionData, updateDependencyStatus.
 */

import type { MutableRefObject } from 'react';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { downloadJSON } from '../../../utils/exportMarkdown';
import { releaseSessionTransition } from '../sessionTransition';
import { drainPendingDependencyStatus } from '../settingsBootstrap';
import {
  isDependencyStatusResponse,
  settleDependencyStatusRequest,
} from '../../../utils/bridgeStartup';

export function registerSessionAndSdkCallbacks(
  options: UseWindowCallbacksOptions,
  tRef: MutableRefObject<UseWindowCallbacksOptions['t']>,
): void {
  const {
    addToast,
    setCurrentSessionId,
    currentSessionIdRef,
    setCustomSessionTitle,
    applyHistoryTitleLocal,
  } = options;

  window.setSessionId = (sessionId: string) => {
    releaseSessionTransition();
    currentSessionIdRef.current = sessionId;
    setCurrentSessionId(sessionId);
  };

  window.addToast = (message, type) => {
    addToast(message, type as 'info' | 'success' | 'warning' | 'error' | undefined);
  };

  window.onExportSessionData = (json) => {
    try {
      const data = JSON.parse(json);
      if (data.sessionId && data.messages) {
        const exportContent = JSON.stringify(data, null, 2);
        const sanitizedTitle = (data.title || 'session')
          .replace(/[<>:"/\\|?*]/g, '_')
          .replace(/\s+/g, '_')
          .substring(0, 50);
        const filename = `${sanitizedTitle}_${data.sessionId.substring(0, 8)}.json`;
        downloadJSON(exportContent, filename);
      } else if (data.error) {
        addToast(data.error, 'error');
      } else {
        addToast(tRef.current('history.exportFailed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to process export data:', error);
      addToast(tRef.current('history.exportFailed'), 'error');
    }
  };

  // =========================================================================
  // SDK Status Callbacks
  // =========================================================================

  const originalUpdateDependencyStatus = window.updateDependencyStatus;
  window.updateDependencyStatus = (jsonStr: string) => {
    // pi 无 npm SDK：后端推送的依赖状态不再存前端状态（settle 请求流程以正常收尾）
    try {
      const data = JSON.parse(jsonStr);
      if (!isDependencyStatusResponse(data)) {
        settleDependencyStatusRequest('error');
        return;
      }
      settleDependencyStatusRequest('ready');
    } catch {
      settleDependencyStatusRequest('error');
    }
    if (
      originalUpdateDependencyStatus &&
      originalUpdateDependencyStatus !== window.updateDependencyStatus
    ) {
      originalUpdateDependencyStatus(jsonStr);
    }
  };
  (window as unknown as Record<string, unknown>)._appUpdateDependencyStatus =
    window.updateDependencyStatus;

  drainPendingDependencyStatus();

  // =========================================================================
  // AI Title Callback
  // =========================================================================

  window.updateSessionTitle = (sessionId: string, title: string) => {
    if (!title || !title.trim() || !sessionId) return;
    // Only apply the title if it matches the current session to prevent
    // stale events from overwriting the wrong session's title.
    if (currentSessionIdRef.current !== sessionId) return;
    setCustomSessionTitle(title.trim());
    applyHistoryTitleLocal(sessionId, title.trim());
  };
}
