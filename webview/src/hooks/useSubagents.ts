import { useMemo } from 'react';
import type { ClaudeMessage, ClaudeRawMessage, ClaudeContentBlock, ToolResultBlock, SubagentHistoryResponse, SubagentInfo, SubagentStatus, TaskEvent, TaskEventMap } from '../types';
import { normalizeToolInput } from '../utils/toolInputNormalization';
import { normalizeToolName } from '../utils/toolConstants';
import { extractResultText, isAsyncAgentInput, parseSpawnAgentMeta, readToolUseStatus } from '../utils/subagentResult';
import { useTaskEvents } from '../contexts/SubagentContext';

// ── pi 官方 subagent 工具适配 ────────────────────────────────────────────────
// pi 的子代理工具名是 `subagent`（examples/extensions/subagent），与 Claude/Codex 的
// task/agent/spawn_agent 不同。调用参数：{agent, task}（单）/ {tasks:[...]}（并行）/
// {chain:[...]}（链式）；子代理不生成独立 session 文件，完整消息在 tool 返回的
// details.results[].messages 里（Java 端保留在 raw.toolUseResult.details）。

interface PiSubagentResult {
  agent?: unknown;
  task?: unknown;
  exitCode?: unknown;
  messages?: unknown[];
  usage?: Record<string, unknown>;
  model?: unknown;
}

/** 从 pi 子代理消息里提取最终文本输出（逆序找 assistant 的最后一个 text 块）。 */
function extractPiFinalOutput(messages: unknown[] | undefined): string | undefined {
  if (!Array.isArray(messages)) return undefined;
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const msg = messages[i] as Record<string, unknown> | null | undefined;
    if (!msg || typeof msg !== 'object' || msg.role !== 'assistant') continue;
    const content = msg.content;
    if (!Array.isArray(content)) continue;
    for (let j = content.length - 1; j >= 0; j -= 1) {
      const part = content[j] as Record<string, unknown> | null | undefined;
      if (part && typeof part === 'object' && part.type === 'text' && typeof part.text === 'string' && part.text.trim()) {
        return part.text.trim();
      }
    }
  }
  return undefined;
}

/** 判断单个 pi 子代理 result 的状态（exitCode: -1=运行中, 0=成功, 其他=失败）。 */
function piResultStatus(result: PiSubagentResult): SubagentStatus | undefined {
  const exitCode = typeof result.exitCode === 'number' ? result.exitCode : undefined;
  if (exitCode === undefined) return undefined;
  if (exitCode === -1) return 'running';
  return exitCode === 0 ? 'completed' : 'error';
}

/**
 * 从 pi 的 `subagent` 工具调用提取 SubagentInfo。
 * 一个 tool_use 生成一条：type=agent 名（单）或 parallel/chain，
 * 完整消息在 toolUseResult.details 里，展开时由 load_subagent_session 拉取。
 */
function extractPiSubagents(
  block: ClaudeContentBlock,
  result: ToolResultBlock | null,
  getToolResultRaw: GetToolResultRawFn,
  messageIndex: number,
): SubagentInfo[] {
  const toolBlock = block as ClaudeContentBlock & { type: 'tool_use'; id?: string; name?: string; input?: unknown };
  const toolUseId = toolBlock.id ?? `subagent-${messageIndex}`;
  const input = (toolBlock.input ?? {}) as Record<string, unknown>;
  const raw = getToolResultRaw(toolUseId);
  const toolUseResult = raw?.toolUseResult;
  const details = (toolUseResult && typeof toolUseResult === 'object'
    ? (toolUseResult as Record<string, unknown>).details
    : undefined) as { mode?: string; results?: PiSubagentResult[] } | undefined;
  const results = Array.isArray(details?.results) ? details.results : [];

  const tasks = Array.isArray(input.tasks) ? input.tasks as Array<Record<string, unknown>> : [];
  const chain = Array.isArray(input.chain) ? input.chain as Array<Record<string, unknown>> : [];
  const mode = details?.mode
    ?? (chain.length > 0 ? 'chain' : tasks.length > 0 ? 'parallel' : 'single');

  const firstResult = results[0];
  const singleAgent = typeof input.agent === 'string' ? input.agent : '';
  const singleTask = typeof input.task === 'string' ? input.task : '';

  // 类型：单模式用 agent 名，并行/链式用 mode
  const type = mode === 'single'
    ? (typeof firstResult?.agent === 'string' ? firstResult.agent : singleAgent || 'subagent')
    : mode;

  // 描述：单模式用 task，并行/链式用任务数摘要
  const description = mode === 'single'
    ? (typeof firstResult?.task === 'string' ? firstResult.task : singleTask)
    : chain.length > 0
      ? `${chain.length} 步链式子代理`
      : tasks.length > 0
        ? `${tasks.length} 个并行子代理`
        : '';

  // 状态：优先 results 的 exitCode，其次 tool_result.is_error
  const resultStatus = results.length > 0
    ? (() => {
        if (results.some((r) => piResultStatus(r) === 'running')) return 'running';
        if (results.some((r) => piResultStatus(r) === 'error')) return 'error';
        return 'completed';
      })()
    : undefined;
  const status: SubagentStatus = resultStatus
    ?? (result?.is_error ? 'error' : 'completed');

  const prompt = mode === 'single'
    ? (typeof firstResult?.task === 'string' ? firstResult.task : singleTask)
    : chain.length > 0
      ? String(chain[0]?.task ?? '')
      : tasks.length > 0
        ? String(tasks[0]?.task ?? '')
        : singleTask;

  const resultText = firstResult
    ? extractPiFinalOutput(firstResult.messages)
    : undefined;

  // 统计：从第一个 result 的 usage 聚合（turns / tokens）
  const totalTurns = results.reduce((acc, r) => acc + (typeof r.usage?.turns === 'number' ? r.usage.turns : 0), 0);
  const totalTokens = results.reduce((acc, r) => acc + (typeof r.usage?.contextTokens === 'number' ? r.usage.contextTokens : 0), 0);

  return [{
    id: toolUseId,
    type,
    description,
    prompt,
    status,
    isAsync: false,
    messageIndex,
    // pi 无独立 agent id，用 tool_use id 作为 load_subagent_session 的定位键
    agentId: toolUseId,
    totalToolUseCount: totalTurns > 0 ? totalTurns : undefined,
    totalTokens: totalTokens > 0 ? totalTokens : undefined,
    resultText: resultText ?? extractResultText(result),
  }];
}


type GetToolResultRawFn = (toolUseId: string) => ClaudeRawMessage | null;

interface UseSubagentsParams {
  messages: ClaudeMessage[];
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[];
  findToolResult: (toolUseId?: string, messageIndex?: number) => ToolResultBlock | null;
  getToolResultRaw: GetToolResultRawFn;
  subagentHistories?: Record<string, SubagentHistoryResponse>;
}

/**
 * Determine subagent status.
 *
 * Async agents (Agent/Task tool invoked with run_in_background:true) only
 * receive a launch acknowledgment tool_result, not a completion signal. The
 * terminal status arrives later via a task_notification event, so while no
 * event has landed the agent is still running.
 *
 * Sync agents (task/agent without run_in_background) run inline: a tool_result
 * means the agent is done.
 */
function determineStatus(
  result: ToolResultBlock | null,
  isAsync: boolean,
  taskEvent: TaskEvent | undefined,
): SubagentStatus {
  if (isAsync) {
    if (taskEvent) {
      return taskEvent.status === 'failed' || taskEvent.status === 'stopped' ? 'error' : 'completed';
    }
    // A failed launch (validation error before the background task was
    // registered) returns an is_error tool_result and never emits a
    // task_notification - surface it as an error instead of staying stuck on
    // "running" forever.
    if (result?.is_error) {
      return 'error';
    }
    return 'running';
  }
  if (!result) {
    return 'running';
  }
  if (result.is_error) {
    return 'error';
  }
  return 'completed';
}

function extractResultMetadata(
  result: ToolResultBlock | null,
  getToolResultRaw: GetToolResultRawFn,
  toolUseId: string,
  taskEvent: TaskEvent | undefined,
): Partial<SubagentInfo> {
  const rawMessage = getToolResultRaw(toolUseId);
  const metadata = rawMessage?.toolUseResult;
  const record = metadata && typeof metadata === 'object' && !Array.isArray(metadata)
    ? (metadata as Record<string, unknown>)
    : null;

  const getString = (value: unknown) => (typeof value === 'string' && value.trim() ? value.trim() : undefined);
  const getNumber = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);
  const toolStats = record?.toolStats && typeof record.toolStats === 'object' && !Array.isArray(record.toolStats)
    ? Object.fromEntries(
      Object.entries(record.toolStats as Record<string, unknown>)
        .filter((entry): entry is [string, number] => typeof entry[1] === 'number' && Number.isFinite(entry[1])),
    )
    : undefined;

  // task_notification wins over toolUseResult: for async agents the launch
  // tool_result carries no usage, so the event is the only source of truth.
  return {
    agentId: taskEvent?.agentId ?? getString(record?.agentId),
    totalDurationMs: taskEvent?.totalDurationMs ?? getNumber(record?.totalDurationMs),
    totalTokens: taskEvent?.totalTokens ?? getNumber(record?.totalTokens),
    totalToolUseCount: taskEvent?.totalToolUseCount ?? getNumber(record?.totalToolUseCount),
    toolStats,
    resultText: taskEvent?.summary ?? extractResultText(result),
  };
}

export function extractSubagentsFromMessages(
  messages: ClaudeMessage[],
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[],
  findToolResult: (toolUseId?: string, messageIndex?: number) => ToolResultBlock | null,
  getToolResultRaw: GetToolResultRawFn,
  taskEvents: TaskEventMap = {},
): SubagentInfo[] {
  const subagents: SubagentInfo[] = [];

  messages.forEach((message, messageIndex) => {
    if (message.type !== 'assistant') return;

    const blocks = getContentBlocks(message);

    blocks.forEach((block) => {
      if (block.type !== 'tool_use') return;

      const toolName = normalizeToolName(block.name ?? '');

      // pi 官方 subagent 工具（单/并行/链式）——适配 pi 生态，与 Claude/Codex 的 task/agent 并列
      if (toolName === 'subagent') {
        const toolUseId = block.id ?? `subagent-${messageIndex}`;
        const result = findToolResult(toolUseId, messageIndex);
        subagents.push(...extractPiSubagents(block, result, getToolResultRaw, messageIndex));
        return;
      }

      // Only process task/agent-style subagent tool calls.
      if (toolName !== 'task' && toolName !== 'agent' && toolName !== 'spawn_agent') return;

      const rawInput = block.input as Record<string, unknown> | undefined;
      const input = rawInput ? normalizeToolInput(block.name, rawInput) as Record<string, unknown> : undefined;
      if (!input) return;

      // Defensive: ensure all string values are actually strings
      const id = String(block.id ?? `task-${messageIndex}-${subagents.length}`);
      const subagentType = String((input.subagent_type as string) ?? (input.subagentType as string) ?? 'Unknown');
      const description = String((input.description as string) ?? '');
      const prompt = String((input.prompt as string) ?? '');

      // Check tool result to determine status
      const toolUseId = block.id ?? '';
      const result = findToolResult(toolUseId, messageIndex);
      const taskEvent = taskEvents[toolUseId];
      // isAsync is read via the shared isAsyncAgentInput helper so the
      // StatusPanel list and the inline Agent cards stay in lockstep. The
      // launch ack text and tool-use status are passed as fallbacks so a
      // background agent whose input lacks run_in_background is still kept
      // "running" until its terminal event lands, instead of being marked
      // completed the instant the ack arrives.
      const toolUseStatus = readToolUseStatus(getToolResultRaw(toolUseId));
      const isAsync = isAsyncAgentInput(input, toolName, result, toolUseStatus);
      const status = determineStatus(result, isAsync, taskEvent);
      const resultMetadata = extractResultMetadata(result, getToolResultRaw, toolUseId, taskEvent);
      const spawnMeta = toolName === 'spawn_agent' ? parseSpawnAgentMeta(input, result) : {};

      subagents.push({
        id,
        type: subagentType,
        description,
        prompt,
        status,
        isAsync,
        messageIndex,
        ...resultMetadata,
        ...(spawnMeta.agentId && { agentId: spawnMeta.agentId }),
        ...(spawnMeta.agentPath && { agentPath: spawnMeta.agentPath }),
      });
    });
  });

  return subagents;
}

export function applySubagentHistoryCompletion(
  subagents: SubagentInfo[],
  subagentHistories: Record<string, SubagentHistoryResponse>,
): SubagentInfo[] {
  return subagents.map((subagent) => {
    if (!subagent.isAsync || subagent.status !== 'running') return subagent;
    const history = subagentHistories[subagent.id]
      ?? (subagent.agentId ? subagentHistories[subagent.agentId] : undefined);
    if (history?.status === 'error') return { ...subagent, status: 'error' as const };
    return history?.completed ? { ...subagent, status: 'completed' as const } : subagent;
  });
}

/**
 * Hook to extract subagent information from Task tool calls.
 */
export function useSubagents({
  messages,
  getContentBlocks,
  findToolResult,
  getToolResultRaw,
  subagentHistories = {},
}: UseSubagentsParams): SubagentInfo[] {
  const taskEvents = useTaskEvents();
  return useMemo(() => {
    const extracted = extractSubagentsFromMessages(
      messages,
      getContentBlocks,
      findToolResult,
      getToolResultRaw,
      taskEvents,
    );
    return applySubagentHistoryCompletion(extracted, subagentHistories);
  }, [messages, getContentBlocks, findToolResult, getToolResultRaw, taskEvents, subagentHistories]);
}
