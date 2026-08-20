import { useCallback, useEffect, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type { PermissionRequest } from '../components/PermissionDialog';
import type { AskUserQuestionRequest } from '../components/AskUserQuestionDialog';
import { sendBridgeEvent } from '../utils/bridge';

interface UseDialogManagementOptions {
  t: TFunction;
}

interface UseDialogManagementReturn {
  // Permission dialog
  permissionDialogOpen: boolean;
  currentPermissionRequest: PermissionRequest | null;
  openPermissionDialog: (request: PermissionRequest) => void;
  handlePermissionApprove: (channelId: string) => void;
  handlePermissionApproveAlways: (channelId: string) => void;
  handlePermissionSkip: (channelId: string) => void;
  forceClosePermissionDialog: (channelId?: string | null) => void;

  // AskUserQuestion dialog
  askUserQuestionDialogOpen: boolean;
  currentAskUserQuestionRequest: AskUserQuestionRequest | null;
  openAskUserQuestionDialog: (request: AskUserQuestionRequest) => void;
  handleAskUserQuestionSubmit: (requestId: string, answers: Record<string, string | string[]>) => void;
  handleAskUserQuestionCancel: (requestId: string) => void;
  forceCloseAskUserQuestionDialog: (requestId?: string | null) => void;
}

/**
 * Hook for managing dialog states (permission, ask user question).
 */
export function useDialogManagement({ t }: UseDialogManagementOptions): UseDialogManagementReturn {
  // Permission dialog state
  const [permissionDialogOpen, setPermissionDialogOpen] = useState(false);
  const [currentPermissionRequest, setCurrentPermissionRequest] = useState<PermissionRequest | null>(null);
  const permissionDialogOpenRef = useRef(false);
  const currentPermissionRequestRef = useRef<PermissionRequest | null>(null);
  const pendingPermissionRequestsRef = useRef<PermissionRequest[]>([]);

  // AskUserQuestion dialog state
  const [askUserQuestionDialogOpen, setAskUserQuestionDialogOpen] = useState(false);
  const [currentAskUserQuestionRequest, setCurrentAskUserQuestionRequest] = useState<AskUserQuestionRequest | null>(null);
  const askUserQuestionDialogOpenRef = useRef(false);
  const currentAskUserQuestionRequestRef = useRef<AskUserQuestionRequest | null>(null);
  const pendingAskUserQuestionRequestsRef = useRef<AskUserQuestionRequest[]>([]);

  // Sync refs with state
  useEffect(() => {
    permissionDialogOpenRef.current = permissionDialogOpen;
    currentPermissionRequestRef.current = currentPermissionRequest;
  }, [permissionDialogOpen, currentPermissionRequest]);

  useEffect(() => {
    askUserQuestionDialogOpenRef.current = askUserQuestionDialogOpen;
    currentAskUserQuestionRequestRef.current = currentAskUserQuestionRequest;
  }, [askUserQuestionDialogOpen, currentAskUserQuestionRequest]);

  // Open permission dialog
  const openPermissionDialog = useCallback((request: PermissionRequest) => {
    // If a permission dialog is currently open, enqueue the new request instead of overriding.
    // This avoids losing follow-up requests when the user denies the current one.
    if (permissionDialogOpenRef.current || currentPermissionRequestRef.current) {
      const currentId = currentPermissionRequestRef.current?.channelId;
      const alreadyQueued = pendingPermissionRequestsRef.current.some(
        (item) => item.channelId === request.channelId
      );
      if (request.channelId !== currentId && !alreadyQueued) {
        pendingPermissionRequestsRef.current.push(request);
      }
      return;
    }

    currentPermissionRequestRef.current = request;
    permissionDialogOpenRef.current = true;
    setCurrentPermissionRequest(request);
    setPermissionDialogOpen(true);
  }, []);

  // Open ask user question dialog
  const openAskUserQuestionDialog = useCallback((request: AskUserQuestionRequest) => {
    // If an ask user question dialog is currently open, enqueue the new request instead of overriding.
    // This avoids losing follow-up requests when multiple questions arrive in quick succession.
    if (askUserQuestionDialogOpenRef.current || currentAskUserQuestionRequestRef.current) {
      const currentId = currentAskUserQuestionRequestRef.current?.requestId;
      const alreadyQueued = pendingAskUserQuestionRequestsRef.current.some(
        (item) => item.requestId === request.requestId
      );
      if (request.requestId !== currentId && !alreadyQueued) {
        pendingAskUserQuestionRequestsRef.current.push(request);
      }
      return;
    }

    currentAskUserQuestionRequestRef.current = request;
    askUserQuestionDialogOpenRef.current = true;
    setCurrentAskUserQuestionRequest(request);
    setAskUserQuestionDialogOpen(true);
  }, []);

  // Process pending permission requests queue
  useEffect(() => {
    if (permissionDialogOpen) return;
    if (currentPermissionRequest) return;
    const next = pendingPermissionRequestsRef.current.shift();
    if (next) {
      openPermissionDialog(next);
    }
  }, [permissionDialogOpen, currentPermissionRequest, openPermissionDialog]);

  // Process pending ask user question requests queue
  useEffect(() => {
    if (askUserQuestionDialogOpen) return;
    if (currentAskUserQuestionRequest) return;
    const next = pendingAskUserQuestionRequestsRef.current.shift();
    if (next) {
      openAskUserQuestionDialog(next);
    }
  }, [askUserQuestionDialogOpen, currentAskUserQuestionRequest, openAskUserQuestionDialog]);

  // Permission handlers
  const handlePermissionApprove = useCallback((channelId: string) => {
    const payload = JSON.stringify({
      channelId,
      allow: true,
      remember: false,
      rejectMessage: null,
    });
    sendBridgeEvent('permission_decision', payload);
    pendingPermissionRequestsRef.current = pendingPermissionRequestsRef.current.filter(
      (item) => item.channelId !== channelId
    );
    permissionDialogOpenRef.current = false;
    currentPermissionRequestRef.current = null;
    setPermissionDialogOpen(false);
    setCurrentPermissionRequest(null);
  }, []);

  const handlePermissionApproveAlways = useCallback((channelId: string) => {
    const payload = JSON.stringify({
      channelId,
      allow: true,
      remember: true,
      rejectMessage: null,
    });
    sendBridgeEvent('permission_decision', payload);
    pendingPermissionRequestsRef.current = pendingPermissionRequestsRef.current.filter(
      (item) => item.channelId !== channelId
    );
    permissionDialogOpenRef.current = false;
    currentPermissionRequestRef.current = null;
    setPermissionDialogOpen(false);
    setCurrentPermissionRequest(null);
  }, []);

  const handlePermissionSkip = useCallback((channelId: string) => {
    const payload = JSON.stringify({
      channelId,
      allow: false,
      remember: false,
      rejectMessage: t('permission.userDenied'),
    });
    sendBridgeEvent('permission_decision', payload);
    pendingPermissionRequestsRef.current = pendingPermissionRequestsRef.current.filter(
      (item) => item.channelId !== channelId
    );
    permissionDialogOpenRef.current = false;
    currentPermissionRequestRef.current = null;
    setPermissionDialogOpen(false);
    setCurrentPermissionRequest(null);
  }, [t]);

  // AskUserQuestion handlers
  const handleAskUserQuestionSubmit = useCallback((requestId: string, answers: Record<string, string | string[]>) => {
    const payload = JSON.stringify({
      requestId,
      answers,
    });
    sendBridgeEvent('ask_user_question_response', payload);
    askUserQuestionDialogOpenRef.current = false;
    currentAskUserQuestionRequestRef.current = null;
    setAskUserQuestionDialogOpen(false);
    setCurrentAskUserQuestionRequest(null);
  }, []);

  const handleAskUserQuestionCancel = useCallback((requestId: string) => {
    const payload = JSON.stringify({
      requestId,
      answers: {},
    });
    sendBridgeEvent('ask_user_question_response', payload);
    askUserQuestionDialogOpenRef.current = false;
    currentAskUserQuestionRequestRef.current = null;
    setAskUserQuestionDialogOpen(false);
    setCurrentAskUserQuestionRequest(null);
  }, []);

  // Force-close helpers — invoked by the backend safety-net handlers when the
  // Java side has already resolved the pending future and we must tear the
  // WebView dialog down to free the queue. We deliberately do NOT send any
  // response back to the backend (it has already written one); doing so would
  // race with the safety-net's empty answer / DENY. The refs are reset
  // synchronously so a new request arriving in the same tick is not silently
  // enqueued behind the now-orphaned dialog. Pending-queue entries for the
  // targeted id (or the entire queue when no id is given) are dropped so a
  // stale request cannot re-open after the dialog is force-closed.
  const forceCloseAskUserQuestionDialog = useCallback((requestId?: string | null) => {
    const targetId = requestId && requestId.length > 0 ? requestId : null;
    pendingAskUserQuestionRequestsRef.current = targetId === null
      ? []
      : pendingAskUserQuestionRequestsRef.current.filter((item) => item.requestId !== targetId);
    // If the targeted ID doesn't match the active dialog, the item was only in
    // the queue (now pruned above) — don't close an unrelated active dialog.
    if (targetId !== null && currentAskUserQuestionRequestRef.current?.requestId !== targetId) {
      return;
    }
    askUserQuestionDialogOpenRef.current = false;
    currentAskUserQuestionRequestRef.current = null;
    setAskUserQuestionDialogOpen(false);
    setCurrentAskUserQuestionRequest(null);
  }, []);

  const forceClosePermissionDialog = useCallback((channelId?: string | null) => {
    const targetId = channelId && channelId.length > 0 ? channelId : null;
    pendingPermissionRequestsRef.current = targetId === null
      ? []
      : pendingPermissionRequestsRef.current.filter((item) => item.channelId !== targetId);
    if (targetId !== null && currentPermissionRequestRef.current?.channelId !== targetId) {
      return;
    }
    permissionDialogOpenRef.current = false;
    currentPermissionRequestRef.current = null;
    setPermissionDialogOpen(false);
    setCurrentPermissionRequest(null);
  }, []);

  return {
    // Permission dialog
    permissionDialogOpen,
    currentPermissionRequest,
    openPermissionDialog,
    handlePermissionApprove,
    handlePermissionApproveAlways,
    handlePermissionSkip,
    forceClosePermissionDialog,

    // AskUserQuestion dialog
    askUserQuestionDialogOpen,
    currentAskUserQuestionRequest,
    openAskUserQuestionDialog,
    handleAskUserQuestionSubmit,
    handleAskUserQuestionCancel,
    forceCloseAskUserQuestionDialog,
  };
}
