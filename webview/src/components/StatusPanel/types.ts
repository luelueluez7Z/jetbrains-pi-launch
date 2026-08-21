import type { TodoItem, SubagentInfo, SubagentHistoryResponse } from '../../types';

export type TabType = 'todo' | 'subagent';

export interface StatusPanelProps {
  todos: TodoItem[];
  subagents: SubagentInfo[];
  subagentHistories?: Record<string, SubagentHistoryResponse>;
  currentSessionId?: string | null;
  currentProvider: string;
  /** Whether the panel is expanded */
  expanded?: boolean;
  /** Whether the conversation is currently streaming (active) */
  isStreaming?: boolean;
  /** Toggle panel expand/collapse (pi 模式下 ContextBar 不渲染，面板自带折叠按钮) */
  onToggleStatusPanel?: () => void;
}

/** magic-context todowrite 状态：pending / in_progress / completed / cancelled */
export const statusClassMap: Record<TodoItem['status'], string> = {
  pending: 'status-pending',
  in_progress: 'status-in-progress',
  completed: 'status-completed',
  cancelled: 'status-cancelled',
};

export const statusIconMap: Record<TodoItem['status'], string> = {
  pending: 'codicon-circle-outline',
  in_progress: 'codicon-loading',
  completed: 'codicon-check',
  cancelled: 'codicon-circle-slash',
};

export const subagentStatusIconMap: Record<SubagentInfo['status'], string> = {
  running: 'codicon-loading',
  completed: 'codicon-check',
  error: 'codicon-error',
};
