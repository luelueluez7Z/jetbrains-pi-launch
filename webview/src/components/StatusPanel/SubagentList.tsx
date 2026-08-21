import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { SubagentHistoryResponse, SubagentInfo } from '../../types';
import { sendBridgeEvent } from '../../utils/bridge';
import { subagentStatusIconMap } from './types';
import SubagentProcessDetails from './SubagentProcessDetails';

interface SubagentListProps {
  subagents: SubagentInfo[];
  histories?: Record<string, SubagentHistoryResponse>;
  currentSessionId?: string | null;
  currentProvider: string;
  isStreaming?: boolean;
}

interface SubagentRowProps {
  subagent: SubagentInfo;
  isExpanded: boolean;
  history: SubagentHistoryResponse | undefined;
  canLoad: boolean;
  onToggle: (id: string) => void;
  t: TFunction;
}

const SubagentRow = memo(({ subagent, isExpanded, history, canLoad, onToggle, t }: SubagentRowProps) => {
  const statusIcon = subagentStatusIconMap[subagent.status] ?? 'codicon-circle-outline';
  const statusClass = `status-${subagent.status}`;

  const handleClick = useCallback(() => {
    onToggle(subagent.id);
  }, [onToggle, subagent.id]);

  return (
    <div className={`subagent-item-wrapper ${statusClass}`}>
      <button
        type="button"
        className={`subagent-item ${statusClass}`}
        onClick={handleClick}
      >
        <span className={`subagent-status-icon ${statusClass}`}>
          <span className={`codicon ${statusIcon}`} />
        </span>
        <span className="subagent-type">{subagent.type || t('statusPanel.subagentTab')}</span>
        <span className="subagent-description" title={subagent.prompt}>
          {subagent.description || subagent.prompt?.slice(0, 50)}
        </span>
        <span className={`subagent-chevron codicon ${isExpanded ? 'codicon-chevron-down' : 'codicon-chevron-right'}`} />
      </button>

      {isExpanded && (
        <SubagentProcessDetails
          agentId={subagent.agentId}
          totalDurationMs={subagent.totalDurationMs}
          totalTokens={subagent.totalTokens}
          totalToolUseCount={subagent.totalToolUseCount}
          resultText={subagent.resultText}
          prompt={subagent.prompt}
          history={history}
          canLoad={canLoad}
        />
      )}
    </div>
  );
});

SubagentRow.displayName = 'SubagentRow';

/**
 * 子代理列表（StatusPanel Subagent tab）。
 * 数据来自 useSubagents（从 task/agent/spawn_agent 工具调用提取）。
 *
 * 注意：pi 后端暂未实现 `load_subagent_session`（读取子代理 sidechain transcript），
 * 因此展开详情时仅展示已从 tool_result 提取的 prompt / 统计 / 结果；
 * 如需子代理的读文件/工具调用明细，需要在 Java 端接入 load_subagent_session。
 */
const SubagentList = memo(({ subagents, histories = {}, currentSessionId, currentProvider }: SubagentListProps) => {
  const { t } = useTranslation();
  const [expandedId, setExpandedId] = useState<string | null>(null);

  // pi 后端已实现 load_subagent_session（从 tool 的 details.results 提取子代理消息），
  // 有会话 id 即可展开拉取详情。
  const canLoad = Boolean(currentSessionId);

  // Keep latest subagents/histories in refs so the polling effect can read fresh
  // values without re-running (and rebuilding the interval) on every change.
  const subagentsRef = useRef(subagents);
  const historiesRef = useRef(histories);
  useEffect(() => { subagentsRef.current = subagents; }, [subagents]);
  useEffect(() => { historiesRef.current = histories; }, [histories]);

  const requestHistory = useCallback((subagent: SubagentInfo) => {
    if (!currentSessionId) return;
    sendBridgeEvent('load_subagent_session', JSON.stringify({
      sessionId: currentSessionId,
      provider: currentProvider,
      agentId: subagent.agentId,
      agentPath: subagent.agentPath,
      description: subagent.description,
      toolUseId: subagent.id,
    }));
  }, [currentProvider, currentSessionId]);

  // Track the expanded row's status so the polling effect re-runs (and clears
  // its interval) when it transitions out of "running".
  const expandedStatus = subagents.find((item) => item.id === expandedId)?.status;

  useEffect(() => {
    if (!expandedId) return;
    const subagent = subagentsRef.current.find((item) => item.id === expandedId);
    if (!subagent || !currentSessionId) return;
    if (!historiesRef.current[expandedId]) {
      requestHistory(subagent);
    }
    if (!currentSessionId || subagent.status !== 'running') return;
    const timer = window.setInterval(() => {
      const current = subagentsRef.current.find((item) => item.id === expandedId);
      if (!current || current.status !== 'running') return;
      requestHistory(current);
    }, 2_000);
    return () => window.clearInterval(timer);
  }, [currentSessionId, expandedId, requestHistory, expandedStatus]);

  const historyById = useMemo(() => histories, [histories]);

  const handleToggleRow = useCallback((id: string) => {
    setExpandedId((prev) => (prev === id ? null : id));
  }, []);

  if (subagents.length === 0) {
    return <div className="status-panel-empty">{t('statusPanel.noSubagents')}</div>;
  }

  return (
    <div className="subagent-list">
      {subagents.map((subagent, index) => {
        const history = historyById[subagent.id] ?? (subagent.agentId ? historyById[subagent.agentId] : undefined);
        return (
          <SubagentRow
            key={subagent.id ?? `subagent-${index}`}
            subagent={subagent}
            isExpanded={expandedId === subagent.id}
            history={history}
            canLoad={canLoad}
            onToggle={handleToggleRow}
            t={t}
          />
        );
      })}
    </div>
  );
});

SubagentList.displayName = 'SubagentList';

export default SubagentList;
