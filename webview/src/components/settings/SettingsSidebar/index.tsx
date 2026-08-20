import styles from './style.module.less';
import { useTranslation } from 'react-i18next';

export type SettingsTab = 'basic' | 'providers' | 'dependencies' | 'usage' | 'permissions' | 'promptEnhancer' | 'commit' | 'mcp' | 'agents' | 'prompts' | 'skills' | 'pet' | 'other' | 'community';

interface SidebarItem {
  key: SettingsTab;
  icon: string;
  labelKey: string; // Changed to i18n translation key
}

// Pi Chat currently owns only the runtime/UI basics. The copied upstream
// settings pages are intentionally kept in the source for later integration,
// but must not expose unrelated provider/Claude/Codex controls to Pi users.
const sidebarItems: SidebarItem[] = [
  { key: 'basic', icon: 'codicon-settings-gear', labelKey: 'settings.basic.title' },
];

interface SettingsSidebarProps {
  currentTab: SettingsTab;
  onTabChange: (tab: SettingsTab) => void;
  isCollapsed: boolean;
  onToggleCollapse: () => void;
  disabledTabs?: SettingsTab[];
  onDisabledTabClick?: (tab: SettingsTab) => void;
}

const SettingsSidebar = ({
  currentTab,
  onTabChange,
  isCollapsed,
  onToggleCollapse,
  disabledTabs = [],
  onDisabledTabClick,
}: SettingsSidebarProps) => {
  const { t } = useTranslation();

  return (
    <div className={`${styles.sidebar} ${isCollapsed ? styles.collapsed : ''}`}>
      <div className={styles.sidebarItems}>
        {sidebarItems.map((item) => {
          const label = t(item.labelKey);
          const isDisabled = disabledTabs.includes(item.key);
          return (
            <div
              key={item.key}
              className={`${styles.sidebarItem} ${currentTab === item.key ? styles.active : ''} ${isDisabled ? styles.disabled : ''}`}
              onClick={() => {
                if (isDisabled) {
                  onDisabledTabClick?.(item.key);
                  return;
                }
                onTabChange(item.key);
              }}
              title={isCollapsed ? label : ''}
              aria-disabled={isDisabled}
            >
              <span className={`codicon ${item.icon}`} />
              <span className={styles.sidebarItemText}>{label}</span>
            </div>
          );
        })}
      </div>

      {/* Collapse toggle button */}
      <div
        className={styles.sidebarToggle}
        onClick={onToggleCollapse}
        title={isCollapsed ? t('settings.sidebar.expand') : t('settings.sidebar.collapse')}
      >
        <span className={`codicon ${isCollapsed ? 'codicon-chevron-right' : 'codicon-chevron-left'}`} />
      </div>
    </div>
  );
};

export default SettingsSidebar;
