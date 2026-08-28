import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { HistoryData, HistorySessionSummary } from '../../types';
import VirtualList from './VirtualList';
import { sendBridgeEvent } from '../../utils/bridge';
import { copyToClipboard } from '../../utils/copyUtils';
import { HistoryListItem, stopPropagationHandler } from './HistoryListItem';
import { HistoryFilters } from './HistoryFilters';
import { HistoryActions } from './HistoryActions';

// Deep search timeout (milliseconds)
const DEEP_SEARCH_TIMEOUT_MS = 30000;

// ================= 会话搜索解析（移植 pi TUI session-selector-search）=================
// 支持：re:<pattern> 正则模式；"短语" 精确匹配（忽略空白差异）；普通 token 大小写不敏感包含。
interface ParsedSearch {
  mode: 'regex' | 'tokens';
  regex: RegExp | null;
  tokens: { kind: 'fuzzy' | 'phrase'; value: string }[];
}

function parseSessionSearchQuery(query: string): ParsedSearch | null {
  const trimmed = query.trim();
  if (!trimmed) return { mode: 'tokens', regex: null, tokens: [] };

  // 正则模式：re:<pattern>
  if (trimmed.startsWith('re:')) {
    const pattern = trimmed.slice(3).trim();
    if (!pattern) return null;
    try {
      return { mode: 'regex', regex: new RegExp(pattern, 'i'), tokens: [] };
    } catch {
      return null;
    }
  }

  // token 模式 + 引号短语（"node cve" → 整体精确匹配）
  const tokens: { kind: 'fuzzy' | 'phrase'; value: string }[] = [];
  let buf = '';
  let inQuote = false;
  let hadUnclosedQuote = false;
  const flush = (kind: 'fuzzy' | 'phrase') => {
    const v = buf.trim();
    buf = '';
    if (v) tokens.push({ kind, value: v });
  };
  for (let i = 0; i < trimmed.length; i++) {
    const ch = trimmed[i];
    if (ch === '"') {
      if (inQuote) { flush('phrase'); inQuote = false; } else { flush('fuzzy'); inQuote = true; }
      continue;
    }
    if (!inQuote && /\s/.test(ch)) { flush('fuzzy'); continue; }
    buf += ch;
  }
  if (inQuote) hadUnclosedQuote = true;
  if (hadUnclosedQuote) {
    // 引号未闭合 → 退化为普通空白分词
    return {
      mode: 'tokens',
      tokens: trimmed.split(/\s+/).map(v => v.trim()).filter(Boolean).map(v => ({ kind: 'fuzzy' as const, value: v })),
      regex: null,
    };
  }
  flush(inQuote ? 'phrase' : 'fuzzy');
  return { mode: 'tokens', tokens, regex: null };
}

function normalizeWsLower(s: string): string {
  return s.toLowerCase().replace(/\s+/g, ' ').trim();
}

/** 匹配会话：搜索目标 = id + 标题（含首消息摘要）+ 会话路径。 */
function matchSessionSearch(s: HistorySessionSummary, parsed: ParsedSearch): boolean {
  const text = `${s.id ?? ''} ${s.title ?? ''} ${s.sessionId}`;
  if (parsed.mode === 'regex') {
    return parsed.regex ? parsed.regex.test(text) : false;
  }
  if (parsed.tokens.length === 0) return true;
  const normalized = normalizeWsLower(text);
  for (const token of parsed.tokens) {
    if (token.kind === 'phrase') {
      if (!normalized.includes(normalizeWsLower(token.value))) return false;
    } else if (!text.toLowerCase().includes(token.value.toLowerCase())) {
      return false;
    }
  }
  return true;
}

const ROOT_STYLE: React.CSSProperties = {
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
};

const LIST_WRAPPER_STYLE: React.CSSProperties = {
  flex: 1,
  overflow: 'hidden',
};

const SPINNER_STYLE: React.CSSProperties = {
  width: '48px',
  height: '48px',
  margin: '0 auto 16px',
  border: '4px solid rgba(133, 133, 133, 0.2)',
  borderTop: '4px solid #858585',
  borderRadius: '50%',
  animation: 'spin 1s linear infinite',
};

const CENTER_BLOCK_STYLE: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
};

const CENTER_BLOCK_FULL_HEIGHT_STYLE: React.CSSProperties = {
  ...CENTER_BLOCK_STYLE,
  height: '100%',
};

const EMPTY_TEXT_STYLE: React.CSSProperties = {
  textAlign: 'center',
  color: '#858585',
};

const EMPTY_ICON_STYLE: React.CSSProperties = {
  fontSize: '48px',
  marginBottom: '16px',
};

const EMPTY_HINT_STYLE: React.CSSProperties = {
  fontSize: '12px',
  marginTop: '8px',
};

interface HistoryViewProps {
  historyData: HistoryData | null;
  currentProvider?: string; // Current provider (claude or codex)
  onLoadSession: (sessionId: string, provider?: string, model?: string, agent?: string) => void;
  onDeleteSession: (sessionId: string) => void; // Delete session callback
  onDeleteSessions: (sessionIds: string[]) => void; // Batch delete sessions callback
  onUpdateTitle: (sessionId: string, newTitle: string) => void; // Update title callback
}

const getComparableTimestamp = (timestamp: string | undefined) => {
  if (!timestamp) {
    return 0;
  }
  const value = new Date(timestamp).getTime();
  return Number.isNaN(value) ? 0 : value;
};

const deduplicateHistorySessions = (sessions: HistorySessionSummary[]) => {
  const deduplicated = new Map<string, HistorySessionSummary>();

  for (const session of sessions) {
    if (!session?.sessionId) {
      continue;
    }

    const existing = deduplicated.get(session.sessionId);
    if (!existing) {
      deduplicated.set(session.sessionId, session);
      continue;
    }

    const existingTs = getComparableTimestamp(existing.lastTimestamp);
    const incomingTs = getComparableTimestamp(session.lastTimestamp);
    const preferred = incomingTs >= existingTs ? session : existing;
    const fallback = preferred === session ? existing : session;

    deduplicated.set(session.sessionId, {
      ...preferred,
      title: preferred.title || fallback.title,
      messageCount: Math.max(preferred.messageCount || 0, fallback.messageCount || 0),
      isFavorited: preferred.isFavorited || fallback.isFavorited,
      favoritedAt: Math.max(preferred.favoritedAt || 0, fallback.favoritedAt || 0) || undefined,
      provider: preferred.provider || fallback.provider,
      model: preferred.model || fallback.model,
      agent: preferred.agent || fallback.agent,
    });
  }

  return Array.from(deduplicated.values());
};

const HistoryView = ({ historyData, currentProvider, onLoadSession, onDeleteSession, onDeleteSessions, onUpdateTitle }: HistoryViewProps) => {
  const { t } = useTranslation();
  const [viewportHeight, setViewportHeight] = useState(() => window.innerHeight || 600);
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null); // Session ID pending deletion
  const [inputValue, setInputValue] = useState(''); // Immediate value of search input
  const [searchQuery, setSearchQuery] = useState(''); // Actual search keyword (debounced)
  const [editingSessionId, setEditingSessionId] = useState<string | null>(null); // Session ID being edited
  const [editingTitle, setEditingTitle] = useState(''); // Title content being edited
  const [isDeepSearching, setIsDeepSearching] = useState(false); // Deep search in-progress state
  const deepSearchTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null); // Deep search timeout timer
  const copyTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null); // Copy status timeout timer
  const [copiedSessionId, setCopiedSessionId] = useState<string | null>(null); // Track which session ID was copied
  const [copyFailedSessionId, setCopyFailedSessionId] = useState<string | null>(null); // Track which session ID copy failed
  const [isSelectionMode, setIsSelectionMode] = useState(false);
  const [selectedSessionIds, setSelectedSessionIds] = useState<Set<string>>(() => new Set());
  const [isDeletingSelected, setIsDeletingSelected] = useState(false);

  // Clean up all timeout timers on unmount
  useEffect(() => {
    return () => {
      if (deepSearchTimeoutRef.current) {
        clearTimeout(deepSearchTimeoutRef.current);
        deepSearchTimeoutRef.current = null;
      }
      if (copyTimeoutRef.current) {
        clearTimeout(copyTimeoutRef.current);
        copyTimeoutRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    const handleResize = () => setViewportHeight(window.innerHeight || 600);
    window.addEventListener('resize', handleResize, { passive: true });
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  // Debounce: update search keyword 300ms after input stops
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearchQuery(inputValue);
    }, 300);

    return () => clearTimeout(timer);
  }, [inputValue]);

  // When history data content updates, stop deep search state and clean up timeout timer.
  // Depend on stable content fields so an existing history list refresh also clears the spinner.
  useEffect(() => {
    if (historyData) {
      setIsDeepSearching(prev => {
        if (prev && deepSearchTimeoutRef.current) {
          clearTimeout(deepSearchTimeoutRef.current);
          deepSearchTimeoutRef.current = null;
        }
        return false;
      });
    }
  }, [historyData?.success, historyData?.total, historyData?.sessions]);

  // 搜索过滤：移植 pi TUI session-selector-search 的解析逻辑
  // 支持 re:<pattern>（正则）、"短语"（精确匹配，忽略空白）、普通 token（大小写不敏感包含）
  const sessions = useMemo(() => {
    const rawSessions = deduplicateHistorySessions(historyData?.sessions ?? []);
    const query = searchQuery.trim();
    if (!query) return rawSessions;

    const parsed = parseSessionSearchQuery(query);
    if (!parsed) return [];
    return rawSessions.filter(s => matchSessionSearch(s, parsed));
  }, [historyData?.sessions, searchQuery]);

  const infoBar = !historyData
    ? ''
    : t('history.totalSessions', {
        count: sessions.length,
        total: historyData.total ?? 0,
      });

  const selectedCount = selectedSessionIds.size;
  const allVisibleSelected = sessions.length > 0 && sessions.every(session => selectedSessionIds.has(session.sessionId));

  useEffect(() => {
    setSelectedSessionIds(prev => {
      if (prev.size === 0) {
        return prev;
      }

      const visibleSessionIds = new Set(sessions.map(session => session.sessionId));
      const next = new Set(Array.from(prev).filter(sessionId => visibleSessionIds.has(sessionId)));
      return next.size === prev.size ? prev : next;
    });
  }, [sessions]);

  const enterSelectionMode = useCallback(() => {
    setIsSelectionMode(true);
  }, []);

  const exitSelectionMode = useCallback(() => {
    setIsSelectionMode(false);
    setSelectedSessionIds(new Set());
    setIsDeletingSelected(false);
  }, []);

  const toggleSessionSelection = useCallback((sessionId: string) => {
    setSelectedSessionIds(prev => {
      const next = new Set(prev);
      if (next.has(sessionId)) {
        next.delete(sessionId);
      } else {
        next.add(sessionId);
      }
      return next;
    });
  }, []);

  const toggleSelectAllVisible = useCallback(() => {
    setSelectedSessionIds(prev => {
      if (sessions.length > 0 && sessions.every(session => prev.has(session.sessionId))) {
        return new Set();
      }
      return new Set(sessions.map(session => session.sessionId));
    });
  }, [sessions]);

  const handleDeleteRequest = useCallback((sessionId: string) => {
    setDeletingSessionId(sessionId);
  }, []);

  const confirmDelete = useCallback(() => {
    if (deletingSessionId) {
      onDeleteSession(deletingSessionId);
      setDeletingSessionId(null);
    }
  }, [deletingSessionId, onDeleteSession]);

  const confirmDeleteSelected = useCallback(() => {
    if (selectedSessionIds.size === 0) {
      setIsDeletingSelected(false);
      return;
    }

    onDeleteSessions(Array.from(selectedSessionIds));
    exitSelectionMode();
  }, [selectedSessionIds, onDeleteSessions, exitSelectionMode]);

  const cancelDelete = useCallback(() => {
    setDeletingSessionId(null);
  }, []);

  const handleEditStart = useCallback((sessionId: string, currentTitle: string) => {
    setEditingSessionId(sessionId);
    setEditingTitle(currentTitle);
  }, []);

  const handleEditSave = useCallback((sessionId: string, title: string) => {
    const trimmedTitle = title.trim();

    if (!trimmedTitle) {
      return; // Title cannot be empty
    }

    if (trimmedTitle.length > 50) {
      return;
    }

    onUpdateTitle(sessionId, trimmedTitle);
    setEditingSessionId(null);
    setEditingTitle('');
  }, [onUpdateTitle]);

  const handleEditCancel = useCallback(() => {
    setEditingSessionId(null);
    setEditingTitle('');
  }, []);

  const handleCopySessionId = useCallback(async (sessionId: string) => {
    if (copyTimeoutRef.current) {
      clearTimeout(copyTimeoutRef.current);
      copyTimeoutRef.current = null;
    }
    const success = await copyToClipboard(sessionId);
    if (success) {
      setCopiedSessionId(sessionId);
      setCopyFailedSessionId(null);
    } else {
      setCopyFailedSessionId(sessionId);
      setCopiedSessionId(null);
    }
    copyTimeoutRef.current = setTimeout(() => {
      setCopiedSessionId(null);
      setCopyFailedSessionId(null);
      copyTimeoutRef.current = null;
    }, 2000);
  }, []);

  const handleItemClick = useCallback((session: HistorySessionSummary, isEditing: boolean) => {
    if (isSelectionMode) {
      toggleSessionSelection(session.sessionId);
      return;
    }
    if (!isEditing) {
      onLoadSession(session.sessionId, session.provider, session.model, session.agent);
    }
  }, [isSelectionMode, toggleSessionSelection, onLoadSession]);

  const handleDeepSearch = useCallback(() => {
    setIsDeepSearching(prev => {
      if (prev) return prev;
      sendBridgeEvent('deep_search_history', currentProvider || 'claude');

      if (deepSearchTimeoutRef.current) {
        clearTimeout(deepSearchTimeoutRef.current);
      }

      deepSearchTimeoutRef.current = setTimeout(() => {
        setIsDeepSearching(false);
        deepSearchTimeoutRef.current = null;
      }, DEEP_SEARCH_TIMEOUT_MS);
      return true;
    });
  }, [currentProvider]);

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setInputValue(e.target.value);
  }, []);

  const handleStartDeleteSelected = useCallback(() => {
    setIsDeletingSelected(true);
  }, []);

  const handleCancelDeleteSelected = useCallback(() => {
    setIsDeletingSelected(false);
  }, []);

  if (!historyData) {
    return (
      <div className="messages-container" style={CENTER_BLOCK_STYLE}>
        <div style={EMPTY_TEXT_STYLE}>
          <div style={SPINNER_STYLE}></div>
          <div>{t('history.loading')}</div>
        </div>
      </div>
    );
  }

  if (!historyData.success) {
    return (
      <div className="messages-container" style={CENTER_BLOCK_STYLE}>
        <div style={EMPTY_TEXT_STYLE}>
          <div style={EMPTY_ICON_STYLE}>⚠️</div>
          <div>{historyData.error ?? t('history.loadFailed')}</div>
        </div>
      </div>
    );
  }

  // Render empty state (no search results or no sessions)
  const renderEmptyState = () => {
    // If search returned no results
    if (searchQuery.trim() && sessions.length === 0) {
      return (
        <div className="messages-container" style={CENTER_BLOCK_FULL_HEIGHT_STYLE}>
          <div style={EMPTY_TEXT_STYLE}>
            <div style={EMPTY_ICON_STYLE}>🔍</div>
            <div>{t('history.noSearchResults')}</div>
            <div style={EMPTY_HINT_STYLE}>{t('history.tryOtherKeywords')}</div>
          </div>
        </div>
      );
    }

    // If there are no sessions at all
    if (!searchQuery.trim() && sessions.length === 0) {
      return (
        <div className="messages-container" style={CENTER_BLOCK_FULL_HEIGHT_STYLE}>
          <div style={EMPTY_TEXT_STYLE}>
            <div style={EMPTY_ICON_STYLE}>📭</div>
            <div>{t('history.noSessions')}</div>
            <div style={EMPTY_HINT_STYLE}>{t('history.noSessionsDesc')}</div>
          </div>
        </div>
      );
    }

    return null;
  };

  const renderHistoryItem = (session: HistorySessionSummary) => (
    <HistoryListItem
      key={`${session.sessionId}-${session.lastTimestamp ?? '0'}`}
      session={session}
      isEditing={editingSessionId === session.sessionId}
      isSelected={selectedSessionIds.has(session.sessionId)}
      isSelectionMode={isSelectionMode}
      isCopied={copiedSessionId === (session.id || session.sessionId)}
      isCopyFailed={copyFailedSessionId === (session.id || session.sessionId)}
      editingTitle={editingSessionId === session.sessionId ? editingTitle : ''}
      searchQuery={searchQuery}
      t={t}
      onItemClick={handleItemClick}
      onSelectionToggle={toggleSessionSelection}
      onEditStart={handleEditStart}
      onEditSave={handleEditSave}
      onEditCancel={handleEditCancel}
      onEditTitleChange={setEditingTitle}
      onDelete={handleDeleteRequest}
      onCopySessionId={handleCopySessionId}
    />
  );

  const listHeight = Math.max(240, viewportHeight - 118);

  return (
    <div style={ROOT_STYLE}>
      <div className="history-header">
        <div className="history-header-main">
          {isSelectionMode ? (
            <div className="history-selection-summary">
              {t('history.selectedSessions', { count: selectedCount })}
            </div>
          ) : (
            <div className="history-info">{infoBar}</div>
          )}
          <HistoryActions
            isSelectionMode={isSelectionMode}
            selectedCount={selectedCount}
            visibleCount={sessions.length}
            allVisibleSelected={allVisibleSelected}
            isDeepSearching={isDeepSearching}
            t={t}
            onEnterSelectionMode={enterSelectionMode}
            onExitSelectionMode={exitSelectionMode}
            onToggleSelectAllVisible={toggleSelectAllVisible}
            onStartDeleteSelected={handleStartDeleteSelected}
            onDeepSearch={handleDeepSearch}
          />
        </div>
        {!isSelectionMode && (
          <HistoryFilters
            inputValue={inputValue}
            onInputChange={handleInputChange}
            t={t}
          />
        )}
        </div>
      <div style={LIST_WRAPPER_STYLE}>
        {sessions.length > 0 ? (
          <VirtualList
            items={sessions}
            itemHeight={78}
            height={listHeight}
            renderItem={renderHistoryItem}
            getItemKey={(session) => `${session.sessionId}-${session.lastTimestamp ?? '0'}`}
            className="messages-container"
          />
        ) : (
          renderEmptyState()
        )}
      </div>

      {/* Delete confirmation dialog */}
      {deletingSessionId && (
        <div className="modal-overlay" onClick={cancelDelete} role="presentation">
          <div className="modal-content" onClick={stopPropagationHandler}>
            <h3>{t('history.confirmDelete')}</h3>
            <p>{t('history.deleteMessage')}</p>
            <div className="modal-actions">
              <button className="modal-btn modal-btn-cancel" onClick={cancelDelete}>
                {t('common.cancel')}
              </button>
              <button className="modal-btn modal-btn-danger" onClick={confirmDelete}>
                {t('common.delete')}
              </button>
            </div>
          </div>
        </div>
      )}

      {isDeletingSelected && (
        <div className="modal-overlay" onClick={handleCancelDeleteSelected} role="presentation">
          <div className="modal-content" onClick={stopPropagationHandler} role="dialog" aria-modal="true" aria-labelledby="delete-selected-title">
            <h3 id="delete-selected-title">{t('history.confirmDeleteSelected')}</h3>
            <p>{t('history.deleteSelectedMessage', { count: selectedCount })}</p>
            <div className="modal-actions">
              <button className="modal-btn modal-btn-cancel" onClick={handleCancelDeleteSelected}>
                {t('common.cancel')}
              </button>
              <button className="modal-btn modal-btn-danger" onClick={confirmDeleteSelected}>
                {t('common.delete')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default HistoryView;
