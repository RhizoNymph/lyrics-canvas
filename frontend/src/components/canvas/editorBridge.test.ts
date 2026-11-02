import { describe, it, expect } from 'vitest';
import { editorBridge, registerEditorBridge } from './editorBridge';
import type { EditorBridge } from './editorBridge';

const make = (): EditorBridge => ({ flush: async () => undefined, reload: () => undefined });

describe('editorBridge registry', () => {
  it('registers, looks up and unregisters per snippet', () => {
    const a = make();
    const off = registerEditorBridge('s1', a);
    expect(editorBridge('s1')).toBe(a);
    expect(editorBridge('s2')).toBeNull();
    off();
    expect(editorBridge('s1')).toBeNull();
  });

  it('a stale unregister does not remove a newer editor for the same snippet', () => {
    const offOld = registerEditorBridge('s1', make());
    const fresh = make();
    const offNew = registerEditorBridge('s1', fresh);
    offOld();
    expect(editorBridge('s1')).toBe(fresh);
    offNew();
    expect(editorBridge('s1')).toBeNull();
  });
});
