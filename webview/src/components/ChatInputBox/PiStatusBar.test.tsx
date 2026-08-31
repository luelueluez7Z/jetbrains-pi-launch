import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PiStatusBar } from './PiStatusBar';

describe('PiStatusBar', () => {
  it('为费用、余额和其他统计段分别保留独立颜色 class', () => {
    const { container } = render(
      <PiStatusBar
        status="正在回复… · 132.5K/256.0K (51%) · cache 94% · ↑8.9K ↓250.7K · $0.142 · plan active · 💰 5h61% 80%"
      />,
    );

    const segments = Array.from(container.querySelectorAll('.pi-status-stats'));
    expect(segments).toHaveLength(6);
    expect(segments.map((segment) => segment.className)).toEqual([
      'pi-status-bar-text pi-status-stats pi-status-context',
      'pi-status-bar-text pi-status-stats pi-status-cache',
      'pi-status-bar-text pi-status-stats pi-status-tokens',
      'pi-status-bar-text pi-status-stats pi-status-cost',
      'pi-status-bar-text pi-status-stats pi-status-plan',
      'pi-status-bar-text pi-status-stats pi-status-balance',
    ]);
    expect(segments[5].textContent).toContain('5h61% 80%');
  });

  it('没有 provider 余额时仍为会话费用着色', () => {
    const { container } = render(<PiStatusBar status="空闲 · $0.142" />);
    const cost = container.querySelector('.pi-status-cost');
    expect(cost).not.toBeNull();
    expect(container.querySelector('.pi-status-balance')).toBeNull();
  });
});
