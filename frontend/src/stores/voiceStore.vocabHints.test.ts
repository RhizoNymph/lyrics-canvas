import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useVoiceStore } from './voiceStore';
import { VOCAB_HINTS_STORAGE_KEY } from '@/lib/voice/vocabHintsSetting';

vi.mock('@/api/client', () => ({ nlpApi: {}, snippetApi: {}, canvasApi: {}, groupApi: {}, tagApi: {} }));

let data: Record<string, string>;

beforeEach(() => {
  data = {};
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => (k in data ? data[k] : null),
    setItem: (k: string, v: string) => {
      data[k] = v;
    },
  });
  useVoiceStore.setState({ vocabHints: true });
});

afterEach(() => vi.unstubAllGlobals());

describe('vocabulary hints setting', () => {
  it('persists a change to the contract key', () => {
    useVoiceStore.getState().setVocabHints(false);
    expect(useVoiceStore.getState().vocabHints).toBe(false);
    expect(data[VOCAB_HINTS_STORAGE_KEY]).toBe('false');
    useVoiceStore.getState().setVocabHints(true);
    expect(data[VOCAB_HINTS_STORAGE_KEY]).toBe('true');
  });

  it('keeps the value for the session when storage throws', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    useVoiceStore.getState().setVocabHints(false);
    expect(useVoiceStore.getState().vocabHints).toBe(false);
    warn.mockRestore();
  });
});
