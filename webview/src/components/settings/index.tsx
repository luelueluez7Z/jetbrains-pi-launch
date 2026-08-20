import { useEffect, useState } from 'react';

interface SettingsViewProps {
  onClose: () => void;
  initialTab?: string;
}

const THEME_OPTIONS = [
  { value: 'system', label: '跟随 IDE' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
];

const FONT_LEVELS = [
  { level: 1, label: '80%' },
  { level: 2, label: '90%（默认）' },
  { level: 3, label: '100%' },
  { level: 4, label: '110%' },
  { level: 5, label: '120%' },
  { level: 6, label: '140%' },
];

const FONT_SCALE: Record<number, number> = { 1: 0.8, 2: 0.9, 3: 1.0, 4: 1.1, 5: 1.2, 6: 1.4 };

function getInitialTheme(): string {
  const saved = localStorage.getItem('theme');
  return saved === 'light' || saved === 'dark' ? saved : 'system';
}

function applyTheme(pref: string) {
  if (pref === 'system') {
    const ide = (window as unknown as { __INITIAL_IDE_THEME__?: string }).__INITIAL_IDE_THEME__;
    document.documentElement.setAttribute('data-theme', ide === 'light' ? 'light' : 'dark');
  } else {
    document.documentElement.setAttribute('data-theme', pref);
  }
}

function getInitialFontLevel(): number {
  const l = parseInt(localStorage.getItem('fontSizeLevel') || '2', 10);
  return l >= 1 && l <= 6 ? l : 2;
}

function applyFontLevel(level: number) {
  document.documentElement.style.setProperty('--font-scale', String(FONT_SCALE[level] ?? 0.9));
}

/** 极简设置页：仅保留主题与字体大小。 */
const SettingsView = ({ onClose }: SettingsViewProps) => {
  const [theme, setTheme] = useState(getInitialTheme);
  const [fontLevel, setFontLevel] = useState(getInitialFontLevel);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  useEffect(() => {
    applyFontLevel(fontLevel);
  }, [fontLevel]);

  const handleThemeChange = (value: string) => {
    setTheme(value);
    localStorage.setItem('theme', value);
  };

  const handleFontChange = (level: number) => {
    setFontLevel(level);
    localStorage.setItem('fontSizeLevel', String(level));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--bg-chat, #1e1e1e)' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '10px 14px',
          borderBottom: '1px solid var(--border-secondary, rgba(127,127,127,0.25))',
          flexShrink: 0,
        }}
      >
        <button
          className="icon-button"
          onClick={onClose}
          title="返回"
          style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 16, color: 'var(--text-secondary)' }}
        >
          ←
        </button>
        <span style={{ fontSize: 14, fontWeight: 600 }}>设置</span>
      </div>

      <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: 24, overflowY: 'auto' }}>
        {/* 主题 */}
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>主题</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {THEME_OPTIONS.map((o) => (
              <button
                key={o.value}
                onClick={() => handleThemeChange(o.value)}
                style={{
                  padding: '6px 14px',
                  borderRadius: 6,
                  border: `1px solid ${theme === o.value ? 'var(--accent-primary, #4b8bf5)' : 'var(--border-secondary, rgba(127,127,127,0.35))'}`,
                  background: theme === o.value ? 'color-mix(in srgb, var(--accent-primary, #4b8bf5) 18%, transparent)' : 'transparent',
                  color: 'var(--text-primary)',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                {o.label}
              </button>
            ))}
          </div>
        </div>

        {/* 字体大小 */}
        <div>
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>字体大小</div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {FONT_LEVELS.map((f) => (
              <button
                key={f.level}
                onClick={() => handleFontChange(f.level)}
                style={{
                  padding: '6px 14px',
                  borderRadius: 6,
                  border: `1px solid ${fontLevel === f.level ? 'var(--accent-primary, #4b8bf5)' : 'var(--border-secondary, rgba(127,127,127,0.35))'}`,
                  background: fontLevel === f.level ? 'color-mix(in srgb, var(--accent-primary, #4b8bf5) 18%, transparent)' : 'transparent',
                  color: 'var(--text-primary)',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};

export default SettingsView;
