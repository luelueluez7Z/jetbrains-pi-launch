import { describe, expect, it } from 'vitest';
import type { ClaudeContentBlock, ClaudeMessage, ToolResultBlock } from '../types';
import { applySubagentHistoryCompletion, extractSubagentsFromMessages } from './useSubagents';

const assistantWithAgent = (toolUseId: string): ClaudeMessage => ({
  type: 'assistant',
  content: '',
  raw: {
    message: {
      content: [
        {
          type: 'tool_use',
          id: toolUseId,
          name: 'Agent',
          input: {
            subagent_type: 'research',
            description: '分析后端历史索引服务的设计模式',
            prompt: '分析 ClaudeHistoryIndexService',
          },
        },
      ],
    },
  },
});

const toolResultMessage = (toolUseId: string): ClaudeMessage => ({
  type: 'user',
  content: '',
  raw: {
    content: [
      {
        type: 'tool_result',
        tool_use_id: toolUseId,
        content: [{ type: 'text', text: 'final report' }],
      },
    ],
    toolUseResult: {
      status: 'completed',
      agentId: 'af5a83aa15ca39691',
      agentType: 'research',
      totalDurationMs: 62629,
      totalTokens: 110586,
      totalToolUseCount: 4,
      toolStats: { readCount: 4, searchCount: 0 },
    },
  } as any,
});

const getContentBlocks = (message: ClaudeMessage): ClaudeContentBlock[] => {
  const raw = message.raw;
  if (!raw || typeof raw === 'string') return [];
  const content = raw.message?.content ?? raw.content;
  return Array.isArray(content) ? content.filter((block): block is ClaudeContentBlock => block.type === 'tool_use') : [];
};

const findToolResult = (messages: ClaudeMessage[]) => (toolUseId?: string): ToolResultBlock | null => {
  for (const message of messages) {
    const raw = message.raw;
    if (!raw || typeof raw === 'string') continue;
    const content = raw.content ?? raw.message?.content;
    if (!Array.isArray(content)) continue;
    const result = content.find((block): block is ToolResultBlock => block.type === 'tool_result' && block.tool_use_id === toolUseId);
    if (result) return result;
  }
  return null;
};

const getToolResultRaw = (messages: ClaudeMessage[]) => (toolUseId: string) => {
  for (const message of messages) {
    const raw = message.raw;
    if (!raw || typeof raw === 'string') continue;
    const content = raw.content ?? raw.message?.content;
    if (Array.isArray(content) && content.some((block) => block.type === 'tool_result' && (block as ToolResultBlock).tool_use_id === toolUseId)) {
      return raw as Record<string, unknown>;
    }
  }
  return null;
};

describe('extractSubagentsFromMessages', () => {
  it('retains Codex spawn_agent path metadata for history requests', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: '',
      raw: {
        message: {
          content: [{
            type: 'tool_use',
            id: 'call-spawn',
            name: 'spawn_agent',
            input: { task_name: 'audit_ui', message: 'Review anchors' },
          }],
        },
      },
    };

    const subagents = extractSubagentsFromMessages(
      [message], getContentBlocks, findToolResult([message]), getToolResultRaw([message]),
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0]).toMatchObject({
      id: 'call-spawn',
      agentPath: 'audit_ui',
      isAsync: true,
      status: 'running',
    });
  });

  it('attaches completed Agent result metadata including stable agent id', () => {
    const messages = [assistantWithAgent('tooluse_backend'), toolResultMessage('tooluse_backend')];

    const subagents = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages),
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0]).toMatchObject({
      id: 'tooluse_backend',
      agentId: 'af5a83aa15ca39691',
      type: 'research',
      description: '分析后端历史索引服务的设计模式',
      status: 'completed',
      totalDurationMs: 62629,
      totalTokens: 110586,
      totalToolUseCount: 4,
    });
    expect(subagents[0].toolStats).toMatchObject({ readCount: 4 });
  });

  const assistantWithAsyncAgent = (toolUseId: string): ClaudeMessage => ({
    type: 'assistant',
    content: '',
    raw: {
      message: {
        content: [
          {
            type: 'tool_use',
            id: toolUseId,
            name: 'Agent',
            input: {
              subagent_type: 'research',
              description: '后台调研 subagent',
              prompt: '调研索引服务设计模式',
              run_in_background: true,
            },
          },
        ],
      },
    },
  });

  // Async agent (Agent tool with run_in_background:true) only gets a launch
  // acknowledgment tool_result; the terminal status arrives later via a
  // task_notification event.
  const launchAckResult = (toolUseId: string): ClaudeMessage => ({
    type: 'user',
    content: '',
    raw: {
      content: [
        {
          type: 'tool_result',
          tool_use_id: toolUseId,
          content: 'Async agent launched successfully.',
        },
      ],
    } as any,
  });

  it('keeps async agent running while only the launch ack has landed', () => {
    const messages = [assistantWithAsyncAgent('tu_spawn'), launchAckResult('tu_spawn')];

    const subagents = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages), {},
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0].status).toBe('running');
  });

  it('completes async agent from its task_notification with event-derived metadata', () => {
    const messages = [assistantWithAsyncAgent('tu_spawn'), launchAckResult('tu_spawn')];
    const taskEvents = {
      tu_spawn: {
        toolUseId: 'tu_spawn',
        status: 'completed' as const,
        summary: '后台调研完成,发现 3 处索引模式',
        totalTokens: 4200,
        totalToolUseCount: 7,
        totalDurationMs: 18000,
      },
    };

    const subagents = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages), taskEvents,
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0]).toMatchObject({
      id: 'tu_spawn',
      status: 'completed',
      resultText: '后台调研完成,发现 3 处索引模式',
      totalTokens: 4200,
      totalToolUseCount: 7,
      totalDurationMs: 18000,
    });
  });

  it('marks async agent as error when task_notification reports failure', () => {
    const messages = [assistantWithAsyncAgent('tu_spawn')];
    const taskEvents = {
      tu_spawn: { toolUseId: 'tu_spawn', status: 'failed' as const },
    };

    const subagents = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages), taskEvents,
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0].status).toBe('error');
  });

  it('marks a failed async launch as error when the ack tool_result is is_error', () => {
    // A validation failure (e.g. "In-process teammates cannot spawn background
    // agents") returns an is_error tool_result before the background task is
    // registered, so no task_notification ever follows - the agent must surface
    // as error, not stay stuck on "running".
    const messages: ClaudeMessage[] = [
      assistantWithAsyncAgent('tu_launch_fail'),
      {
        type: 'user',
        content: '',
        raw: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: 'tu_launch_fail',
              content: 'In-process teammates cannot spawn background agents',
              is_error: true,
            },
          ],
        } as any,
      },
    ];

    const subagents = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages), {},
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0].status).toBe('error');
  });

  it('finalizes only async agents whose sidechain history ends in end_turn', () => {
    const messages = [assistantWithAsyncAgent('tu_spawn'), launchAckResult('tu_spawn')];
    const extracted = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages), {},
    );

    expect(applySubagentHistoryCompletion(extracted, {
      tu_spawn: { success: true, completed: false, messages: [] },
    })[0].status).toBe('running');

    expect(applySubagentHistoryCompletion(extracted, {
      tu_spawn: { success: true, completed: true, messages: [] },
    })[0].status).toBe('completed');
  });

  it('does not overwrite a task_notification error with sidechain completion', () => {
    const messages = [assistantWithAsyncAgent('tu_spawn')];
    const taskEvents = {
      tu_spawn: { toolUseId: 'tu_spawn', status: 'failed' as const },
    };
    const extracted = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages), taskEvents,
    );

    expect(applySubagentHistoryCompletion(extracted, {
      tu_spawn: { success: true, completed: true, messages: [] },
    })[0].status).toBe('error');
  });

  // ── pi 官方 subagent 工具适配 ────────────────────────────────────────────
  it('extracts a single-mode pi subagent from tool details', () => {
    const call: ClaudeMessage = {
      type: 'assistant',
      content: '',
      raw: {
        message: {
          content: [{
            type: 'tool_use',
            id: 'call_pi_subagent',
            name: 'subagent',
            input: { agent: 'scout', task: '调研认证代码' },
          }],
        },
      },
    };
    const result: ClaudeMessage = {
      type: 'user',
      content: '',
      raw: {
        content: [{
          type: 'tool_result',
          tool_use_id: 'call_pi_subagent',
          content: [{ type: 'text', text: 'final report' }],
        }],
        toolUseResult: {
          details: {
            mode: 'single',
            results: [{
              agent: 'scout',
              task: '调研认证代码',
              exitCode: 0,
              messages: [
                { role: 'assistant', content: [{ type: 'toolCall', name: 'read', arguments: { file_path: 'src/auth.ts' } }] },
                { role: 'assistant', content: [{ type: 'text', text: 'found auth' }] },
              ],
              usage: { turns: 2, contextTokens: 500 },
            }],
          },
        },
      } as any,
    };

    const messages = [call, result];
    const subagents = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages),
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0]).toMatchObject({
      id: 'call_pi_subagent',
      type: 'scout',
      description: '调研认证代码',
      prompt: '调研认证代码',
      status: 'completed',
      agentId: 'call_pi_subagent',
      // resultText 优先取子代理消息里的最终文本输出
      resultText: 'found auth',
      totalToolUseCount: 2,
    });
  });

  it('marks a failed/parallel pi subagent and falls back when details are missing', () => {
    // 并行模式：exitCode 非 0 → error
    const parallel: ClaudeMessage = {
      type: 'assistant',
      content: '',
      raw: {
        message: {
          content: [{
            type: 'tool_use',
            id: 'call_parallel',
            name: 'subagent',
            input: { tasks: [{ agent: 'worker', task: 'A' }, { agent: 'worker', task: 'B' }] },
          }],
        },
      },
    };
    const parallelResult: ClaudeMessage = {
      type: 'user',
      content: '',
      raw: {
        content: [{
          type: 'tool_result',
          tool_use_id: 'call_parallel',
          content: 'Parallel: 1/2 succeeded',
          is_error: true,
        }],
        toolUseResult: {
          details: {
            mode: 'parallel',
            results: [
              { agent: 'worker', task: 'A', exitCode: 0, messages: [], usage: { turns: 1 } },
              { agent: 'worker', task: 'B', exitCode: 1, messages: [], usage: { turns: 0 } },
            ],
          },
        },
      } as any,
    };

    const messages = [parallel, parallelResult];
    const subagents = extractSubagentsFromMessages(
      messages, getContentBlocks, findToolResult(messages), getToolResultRaw(messages),
    );

    expect(subagents).toHaveLength(1);
    expect(subagents[0]).toMatchObject({
      id: 'call_parallel',
      type: 'parallel',
      status: 'error',
      description: '2 个并行子代理',
    });

    // 无 details（如历史回放未保留）时退化为单条，status 从 tool_result.is_error 推导
    const noDetails: ClaudeMessage[] = [{
      type: 'assistant',
      content: '',
      raw: {
        message: {
          content: [{
            type: 'tool_use',
            id: 'call_nodetails',
            name: 'subagent',
            input: { agent: 'planner', task: '制定计划' },
          }],
        },
      },
    }];
    const fallback = extractSubagentsFromMessages(
      noDetails, getContentBlocks, findToolResult(noDetails), getToolResultRaw(noDetails),
    );
    expect(fallback).toHaveLength(1);
    expect(fallback[0]).toMatchObject({ id: 'call_nodetails', type: 'planner', status: 'completed' });
  });
});
