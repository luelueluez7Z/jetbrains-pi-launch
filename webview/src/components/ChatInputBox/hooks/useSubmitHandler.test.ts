import { renderHook } from '@testing-library/react';
import type { Attachment } from '../types.js';
import { useSubmitHandler } from './useSubmitHandler.js';

function createAttachment(id: string): Attachment {
  return { id, fileName: `${id}.txt`, mediaType: 'text/plain', data: 'ZGF0YQ==' };
}

describe('useSubmitHandler', () => {
  it('does nothing when input is empty and no attachments', () => {
    const clearInput = vi.fn();
    const close = vi.fn();
    const onSubmit = vi.fn();
    const recordInputHistory = vi.fn();

    const { result } = renderHook(() =>
      useSubmitHandler({
        getTextContent: () => '',
        invalidateCache: vi.fn(),
        attachments: [],
        isLoading: false,
        clearInput,
        cancelPendingInput: vi.fn(),
        externalAttachments: undefined,
        setInternalAttachments: vi.fn(),
        fileCompletion: { close },
        commandCompletion: { close },
        agentCompletion: { close },
        dollarCommandCompletion: { close },
        recordInputHistory,
        onSubmit,
        t: (key) => key,
      })
    );

    result.current();
    expect(onSubmit).not.toHaveBeenCalled();
    expect(clearInput).not.toHaveBeenCalled();
    expect(recordInputHistory).not.toHaveBeenCalled();
  });

  it('submits content, closes completions, records history, and clears input', () => {
    vi.useFakeTimers();
    const clearInput = vi.fn();
    const recordInputHistory = vi.fn();
    const close = vi.fn();
    const onSubmit = vi.fn();
    const invalidateCache = vi.fn();

    const { result } = renderHook(() =>
      useSubmitHandler({
        getTextContent: () => 'hello',
        invalidateCache,
        attachments: [createAttachment('a1')],
        isLoading: false,
        clearInput,
        cancelPendingInput: vi.fn(),
        externalAttachments: undefined,
        setInternalAttachments: vi.fn(),
        fileCompletion: { close },
        commandCompletion: { close },
        agentCompletion: { close },
        dollarCommandCompletion: { close },
        recordInputHistory,
        onSubmit,
        t: (key) => key,
      })
    );

    result.current();
    expect(invalidateCache).toHaveBeenCalled();
    expect(close).toHaveBeenCalledTimes(4);
    expect(recordInputHistory).toHaveBeenCalledWith('hello');
    expect(clearInput).toHaveBeenCalled();

    vi.advanceTimersByTime(20);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith('hello', [createAttachment('a1')], undefined);
    vi.useRealTimers();
  });
});
