import { describe, expect, it } from 'vitest';
import { normalizeTodoStatus } from './todoShared';

describe('normalizeTodoStatus', () => {
  it('maps completed / done to completed', () => {
    expect(normalizeTodoStatus('completed')).toBe('completed');
    expect(normalizeTodoStatus('done')).toBe('completed');
  });

  it('maps in-progress variants to in_progress', () => {
    expect(normalizeTodoStatus('in_progress')).toBe('in_progress');
    expect(normalizeTodoStatus('in-progress')).toBe('in_progress');
    expect(normalizeTodoStatus('active')).toBe('in_progress');
    expect(normalizeTodoStatus('running')).toBe('in_progress');
  });

  it('maps cancelled variants to cancelled (magic-context todowrite)', () => {
    expect(normalizeTodoStatus('cancelled')).toBe('cancelled');
    expect(normalizeTodoStatus('canceled')).toBe('cancelled');
    expect(normalizeTodoStatus('aborted')).toBe('cancelled');
    expect(normalizeTodoStatus('skipped')).toBe('cancelled');
  });

  it('normalizes case and whitespace', () => {
    expect(normalizeTodoStatus('  Completed ')).toBe('completed');
    expect(normalizeTodoStatus('CANCELLED')).toBe('cancelled');
  });

  it('falls back to pending for unknown / missing status', () => {
    expect(normalizeTodoStatus('weird-status')).toBe('pending');
    expect(normalizeTodoStatus(undefined)).toBe('pending');
    expect(normalizeTodoStatus(42)).toBe('pending');
    expect(normalizeTodoStatus('')).toBe('pending');
  });
});
