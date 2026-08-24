import { describe, expect, it } from 'vitest';
import { resolveProviderModels } from './resolveProviderModels';

describe('resolveProviderModels（纯 pi）', () => {
  it('returns cliModels for pi', () => {
    const models = [{ id: 'pi::auto', label: 'Auto' }];
    expect(resolveProviderModels({ provider: 'pi', cliModels: models })).toEqual(models);
  });

  it('returns empty for non-pi providers', () => {
    expect(resolveProviderModels({ provider: 'claude', cliModels: [{ id: 'x', label: 'X' }] })).toEqual([]);
    expect(resolveProviderModels({ provider: 'codex', cliModels: [{ id: 'x', label: 'X' }] })).toEqual([]);
  });
});
