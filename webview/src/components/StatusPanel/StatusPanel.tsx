import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import TodoList from './TodoList';
import SubagentList from './SubagentList';
import type { TabType, StatusPanelProps } from './types';
import './StatusPanel.css';

/**
 * 状态面板：Todos（任务列表）+ 子代理（Subagent 列表）。
 * 点击 tab 弹出对应内容（popover 模式，自 cc-gui 移植的精简版，
 * 移除了 Files/文件变更 tab 及 undo/discard/keep-all 交互）。
 */
const StatusPanel = ({
  todos,
  subagents,
  subagentHistories,
  currentSessionId,
  currentProvider,
  expanded = true,
  isStreaming = false,
  onToggleStatusPanel,
}: StatusPanelProps) => {
  const { t } = useTranslation();
  const [openPopover, setOpenPopover] = useState<TabType | null>(null);

  const hasTodos = todos.length > 0;
  const hasSubagents = subagents.length > 0;

  // Calculate todo stats
  const { completedCount, totalCount, hasInProgressTodo } = useMemo(() => {
    const completed = todos.filter((todo) => todo.status === 'completed').length;
    const inProgress = todos.some((todo) => todo.status === 'in_progress');
    return { completedCount: completed, totalCount: todos.length, hasInProgressTodo: inProgress };
  }, [todos]);

  // Calculate subagent stats
  const { subagentCompletedCount, subagentTotalCount, hasRunningSubagent } = useMemo(() => {
    const completed = subagents.filter((s) => s.status === 'completed').length;
    const running = subagents.some((s) => s.status === 'running');
    return { subagentCompletedCount: completed, subagentTotalCount: subagents.length, hasRunningSubagent: running };
  }, [subagents]);

  if (!expanded) {
    return null;
  }

  const renderPopoverContent = () => {
    switch (openPopover) {
      case 'todo':
        return <TodoList todos={todos} />;
      case 'subagent':
        return (
          <SubagentList
            subagents={subagents}
            histories={subagentHistories}
            currentSessionId={currentSessionId}
            currentProvider={currentProvider}
            isStreaming={isStreaming}
          />
        );
      default:
        return null;
    }
  };

  return (
    <div className="status-panel">
      {/* Tab Header */}
      <div className="status-panel-tabs">
        {/* Todo Tab */}
        <div
          className={`status-panel-tab ${openPopover === 'todo' ? 'active' : ''}`}
          onClick={() => setOpenPopover((prev) => (prev === 'todo' ? null : 'todo'))}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              setOpenPopover((prev) => (prev === 'todo' ? null : 'todo'));
            }
          }}
        >
          <span className="codicon codicon-checklist" />
          <span className="tab-label">{t('statusPanel.tasksTab')}</span>
          {hasTodos && (
            <span className="tab-progress">
              {completedCount}/{totalCount}
            </span>
          )}
          {isStreaming && hasInProgressTodo && (
            <span className="codicon codicon-loading status-panel-tab-loading" />
          )}
        </div>

        {/* Subagent Tab */}
        <div
          className={`status-panel-tab ${openPopover === 'subagent' ? 'active' : ''}`}
          onClick={() => setOpenPopover((prev) => (prev === 'subagent' ? null : 'subagent'))}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              setOpenPopover((prev) => (prev === 'subagent' ? null : 'subagent'));
            }
          }}
        >
          <span className="codicon codicon-hubot" />
          <span className="tab-label">{t('statusPanel.subagentTab')}</span>
          {hasSubagents && (
            <span className="tab-progress">
              {subagentCompletedCount}/{subagentTotalCount}
            </span>
          )}
          {isStreaming && hasRunningSubagent && (
            <span className="codicon codicon-loading status-panel-tab-loading" />
          )}
        </div>

        {/* Collapse button — pi 模式下 ContextBar 不渲染，面板自带折叠入口 */}
        {onToggleStatusPanel && (
          <button
            type="button"
            className="status-panel-collapse"
            onClick={onToggleStatusPanel}
            title={t('statusPanel.collapse')}
            aria-label={t('statusPanel.collapse')}
          >
            <span className="codicon codicon-chevron-down" />
          </button>
        )}
      </div>

      {/* Popover Content */}
      {openPopover && (
        <div className="status-panel-popover">
          {renderPopoverContent()}
        </div>
      )}
    </div>
  );
};

export default StatusPanel;
