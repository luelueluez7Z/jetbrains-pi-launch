import { useCallback, useState } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ContextMenu } from '../ContextMenu';
import { copyToClipboard } from '../../utils/copyUtils';

interface CommandCopyContextMenuProps {
  command: string;
  children: ReactNode;
}

interface ContextMenuPosition {
  x: number;
  y: number;
}

/**
 * Replaces JCEF's default context menu for a rendered shell command with a
 * command-specific copy action, avoiding the browser's unrelated Print item.
 */
export function CommandCopyContextMenu({ command, children }: CommandCopyContextMenuProps) {
  const { t } = useTranslation();
  const [menuPosition, setMenuPosition] = useState<ContextMenuPosition | null>(null);

  const handleContextMenu = useCallback((event: MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    if (!command.trim()) return;
    setMenuPosition({ x: event.clientX, y: event.clientY });
  }, [command]);

  const closeMenu = useCallback(() => setMenuPosition(null), []);
  const handleCopy = useCallback(() => {
    void copyToClipboard(command);
  }, [command]);

  return (
    <>
      <div className="bash-command-block" onContextMenu={handleContextMenu}>
        {children}
      </div>
      {menuPosition && (
        <ContextMenu
          x={menuPosition.x}
          y={menuPosition.y}
          onClose={closeMenu}
          items={[{
            label: t('contextMenu.copyCommand', 'Copy command'),
            action: handleCopy,
          }]}
        />
      )}
    </>
  );
}
