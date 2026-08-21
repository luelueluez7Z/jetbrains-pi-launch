import { memo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ToolInput, ToolResultBlock } from '../../types';
import { useIsToolDenied } from '../../hooks/useIsToolDenied';
import { openFile } from '../../utils/bridge';
import { getFolderIcon } from '../../utils/fileIcons';
import { truncate } from '../../utils/helpers';

interface LsToolBlockProps {
  name?: string;
  input?: ToolInput;
  result?: ToolResultBlock | null;
  /** Unique ID of the tool call, used to determine if the user denied permission */
  toolId?: string;
}

const ROOT_STYLE: React.CSSProperties = { margin: '12px 0' };

const TASK_CONTAINER_STYLE: React.CSSProperties = { margin: 0 };

const TITLE_SECTION_STYLE: React.CSSProperties = { overflow: 'hidden' };

const ICON_STYLE: React.CSSProperties = { flexShrink: 0 };

const TITLE_TEXT_STYLE: React.CSSProperties = { flexShrink: 0 };

const SUMMARY_STYLE: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  marginLeft: '8px',
  maxWidth: 'fit-content',
  overflow: 'hidden',
};

const SUMMARY_FILE_ICON_STYLE: React.CSSProperties = {
  marginRight: '4px',
  display: 'flex',
  alignItems: 'center',
  width: '16px',
  height: '16px',
};

const LIST_STYLE: React.CSSProperties = {
  padding: '6px 8px',
  borderTop: '1px solid var(--border-primary)',
  maxHeight: '280px',
  overflowY: 'auto',
};

const ENTRY_STYLE: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: '6px',
  padding: '2px 4px',
  borderRadius: '4px',
  fontSize: '12px',
  lineHeight: 1.6,
  cursor: 'pointer',
};

const ENTRY_NAME_STYLE: React.CSSProperties = {
  fontFamily: 'var(--idea-editor-font-family, monospace)',
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const ENTRY_DIR_STYLE: React.CSSProperties = {
  ...ENTRY_NAME_STYLE,
  color: 'var(--text-link, #4a90d9)',
  fontWeight: 500,
};

const ENTRY_ICON_STYLE: React.CSSProperties = {
  width: '16px',
  fontSize: '13px',
  flexShrink: 0,
};

const FALLBACK_STYLE: React.CSSProperties = {
  fontSize: '12px',
  color: 'var(--text-secondary)',
  padding: '4px',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
};

/** Extract plain text from a tool_result (string or array-of-text blocks). */
function extractResultText(result?: ToolResultBlock | null): string {
  if (!result) return '';
  const content = result.content;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .filter((c) => c.type === 'text' && typeof c.text === 'string')
      .map((c) => c.text as string)
      .join('\n');
  }
  return '';
}

/**
 * Dedicated rendering for the pi `ls` tool: shows the listed directory and,
 * once the tool completes, the resulting file/directory entries (one per line,
 * directories marked with a trailing '/' and a folder icon).
 */
const LsToolBlock = memo(function LsToolBlock({ input, result, toolId }: LsToolBlockProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);
  const isDenied = useIsToolDenied(toolId);

  if (!input) return null;

  const isCompleted = (result !== undefined && result !== null) || isDenied;
  const isError = isDenied || (isCompleted && result?.is_error === true);

  // ls input: { path?, limit? }
  const dirPath =
    (typeof input.path === 'string' && input.path.trim() ? input.path.trim() : undefined) ??
    (typeof input.directory === 'string' && input.directory.trim() ? input.directory.trim() : undefined) ??
    '.';
  const limit = typeof input.limit === 'number' ? input.limit : undefined;

  const rawOutput = extractResultText(result);
  // Filter out the trailing "[... limit reached]" notice (pi appends actionable
  // notices after a blank line) and truncation markers; keep only real entries.
  const entries = rawOutput
    .split('\n')
    .map((line) => line.trimEnd())
    .filter((line) => line.length > 0 && !line.startsWith('[') && !line.startsWith('... ('));

  const summary = `${dirPath}${limit !== undefined ? ` (limit ${limit})` : ''}`;
  const displaySummary = truncate(summary, 80);

  const handleDirClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    openFile(dirPath === '.' ? undefined : dirPath);
  };

  const handleEntryClick = (entry: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const full = dirPath === '.' ? entry : `${dirPath.replace(/\/+$/, '')}/${entry}`;
    openFile(full);
  };

  return (
    <div style={ROOT_STYLE}>
      <div className="task-container" style={TASK_CONTAINER_STYLE}>
        <div className="task-header" onClick={() => setExpanded((prev) => !prev)}>
          <div className="task-title-section" style={TITLE_SECTION_STYLE}>
            <span className="codicon codicon-folder tool-title-icon" style={ICON_STYLE} />
            <span className="tool-title-text" style={TITLE_TEXT_STYLE}>
              {t('tools.listFiles')}
            </span>
            <span
              className="tool-title-summary clickable-file"
              onClick={handleDirClick}
              style={SUMMARY_STYLE}
            >
              <span style={SUMMARY_FILE_ICON_STYLE} dangerouslySetInnerHTML={{ __html: getFolderIcon(dirPath) }} />
              {displaySummary}
            </span>
          </div>
          <div className={`tool-status-indicator ${isError ? 'error' : isCompleted ? 'completed' : 'pending'}`} />
        </div>

        {expanded && isCompleted && !isError && entries.length > 0 && (
          <div className="task-details" style={LIST_STYLE}>
            {entries.map((entry, idx) => {
              const isDir = entry.endsWith('/');
              const entryName = isDir ? entry.slice(0, -1) : entry;
              return (
                <div
                  key={idx}
                  className="file-list-item"
                  style={ENTRY_STYLE}
                  onClick={(e) => handleEntryClick(entry, e)}
                  title={entry}
                >
                  <span
                    className={`codicon ${isDir ? 'codicon-folder' : 'codicon-file'}`}
                    style={ENTRY_ICON_STYLE}
                  />
                  <span style={isDir ? ENTRY_DIR_STYLE : ENTRY_NAME_STYLE}>
                    {entryName}{isDir ? '/' : ''}
                  </span>
                </div>
              );
            })}
          </div>
        )}

        {expanded && isCompleted && !isError && entries.length === 0 && (
          <div className="task-details" style={FALLBACK_STYLE}>
            {rawOutput || t('tools.noResults')}
          </div>
        )}

        {expanded && isError && (
          <div className="task-details" style={FALLBACK_STYLE}>
            {rawOutput || t('tools.toolError')}
          </div>
        )}
      </div>
    </div>
  );
});

export default LsToolBlock;
