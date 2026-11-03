import { create } from 'zustand';
import { tagApi, tagPresetApi } from '@/api/client';
import type { TagPresetUpdateBody, TagUpdateBody } from '@/api/client';
import type { Tag, TagPreset } from '@/types';
import {
  TagApiError, byName, checkName, checkPresetExpression, describeNameProblem, embedTag, isHexColor, patchEmbeddedTag,
  removeEmbeddedTag, setEmbeddedTag, toTagApiError,
} from '@/lib/tags';
import type { TagOperation, TagResult, TogglePlan } from '@/lib/tags';
import { useCanvasStore } from './canvasStore';
import { useTagFilterStore } from './tagFilterStore';
import { useRhymeStore } from './rhymeStore';

export type LoadState = 'idle' | 'loading' | 'ready' | 'failed';

/**
 * The user's tags and tag presets (both user-wide, shared by every canvas). Every action resolves to a
 * TagResult and never throws. Tags embedded in loaded snippets are kept in step through
 * canvasStore.updateEmbeddedTags.
 */
export interface TagStoreState {
  /** Sorted by lower(name), like the server. */
  tags: Tag[];
  /** Sorted by lower(name). */
  presets: TagPreset[];
  loadState: LoadState;
  loadError: TagApiError | null;

  /** Fetch tags and presets (in parallel). */
  load: () => Promise<TagResult<void>>;
  refreshPresets: () => Promise<TagResult<void>>;
  createTag: (name: string, color?: string) => Promise<TagResult<Tag>>;
  /** A rename also refetches presets (the server rewrote them) and renames the tag in the open filters. */
  updateTag: (id: string, patch: TagUpdateBody) => Promise<TagResult<Tag>>;
  deleteTag: (id: string) => Promise<TagResult<void>>;
  /** Apply a picker toggle to every snippet in the plan; succeeded links are kept even if others fail. */
  applyToggle: (plan: TogglePlan) => Promise<TagResult<void>>;
  createPreset: (name: string, expression: string) => Promise<TagResult<TagPreset>>;
  updatePreset: (id: string, patch: TagPresetUpdateBody) => Promise<TagResult<TagPreset>>;
  deletePreset: (id: string) => Promise<TagResult<void>>;
}

const sorted = <T extends { name: string }>(items: readonly T[]): T[] => [...items].sort(byName);
const upsert = <T extends { id: string; name: string }>(items: readonly T[], item: T): T[] =>
  sorted([...items.filter((x) => x.id !== item.id), item]);

function fail<T>(error: TagApiError): TagResult<T> {
  console.warn('tags: request failed', { operation: error.operation, kind: error.kind, status: error.status });
  return { ok: false, error };
}

function localNameError(raw: string, operation: TagOperation, preset: boolean): TagApiError | null {
  const check = checkName(raw);
  if (check.ok) return null;
  return new TagApiError(preset ? 'invalid_preset_name' : 'invalid_tag_name', operation, describeNameProblem(check.problem));
}

export const useTagStore = create<TagStoreState>((set, get) => ({
  tags: [],
  presets: [],
  loadState: 'idle',
  loadError: null,

  load: async () => {
    set({ loadState: 'loading', loadError: null });
    const [tags, presets] = await Promise.allSettled([tagApi.list(), tagPresetApi.list()]);
    if (tags.status === 'rejected') {
      const error = toTagApiError(tags.reason, 'load_tags');
      set({ loadState: 'failed', loadError: error });
      return fail(error);
    }
    if (presets.status === 'rejected') {
      const error = toTagApiError(presets.reason, 'load_presets');
      set({ tags: sorted(tags.value.data), loadState: 'failed', loadError: error });
      return fail(error);
    }
    set({ tags: sorted(tags.value.data), presets: sorted(presets.value.data), loadState: 'ready', loadError: null });
    return { ok: true, value: undefined };
  },

  refreshPresets: async () => {
    try {
      const res = await tagPresetApi.list();
      set({ presets: sorted(res.data) });
      return { ok: true, value: undefined };
    } catch (err) {
      return fail(toTagApiError(err, 'load_presets'));
    }
  },

  createTag: async (name, color) => {
    const local = localNameError(name, 'create_tag', false);
    if (local) return fail(local);
    if (color !== undefined && !isHexColor(color)) return fail(new TagApiError('invalid_color', 'create_tag', 'Colors are #rrggbb'));
    try {
      const res = await tagApi.create(name.trim(), color);
      set({ tags: upsert(get().tags, res.data) });
      return { ok: true, value: res.data };
    } catch (err) {
      return fail(toTagApiError(err, 'create_tag'));
    }
  },

  updateTag: async (id, patch) => {
    const before = get().tags.find((t) => t.id === id);
    const body: TagUpdateBody = {};
    if (patch.name !== undefined) {
      const local = localNameError(patch.name, 'update_tag', false);
      if (local) return fail(local);
      body.name = patch.name.trim();
    }
    if (patch.color !== undefined) {
      if (!isHexColor(patch.color)) return fail(new TagApiError('invalid_color', 'update_tag', 'Colors are #rrggbb'));
      body.color = patch.color;
    }
    let updated: Tag;
    try {
      updated = (await tagApi.update(id, body)).data;
    } catch (err) {
      const error = toTagApiError(err, 'update_tag');
      if (error.kind === 'tag_not_found') {
        set({ tags: get().tags.filter((t) => t.id !== id) });
        useCanvasStore.getState().updateEmbeddedTags((m) => removeEmbeddedTag(m, id));
      }
      return fail(error);
    }
    set({ tags: upsert(get().tags, updated) });
    useCanvasStore.getState().updateEmbeddedTags((m) => patchEmbeddedTag(m, embedTag(updated)));
    if (before !== undefined && before.name !== updated.name) {
      useTagFilterStore.getState().dispatch({ type: 'renameTag', from: before.name, to: updated.name });
      useRhymeStore.getState().renameTagInFilter(before.name, updated.name);
      const refreshed = await get().refreshPresets();
      if (!refreshed.ok) console.warn('tags: presets not refreshed after rename', { tagId: id });
    }
    return { ok: true, value: updated };
  },

  deleteTag: async (id) => {
    try {
      await tagApi.delete(id);
    } catch (err) {
      const error = toTagApiError(err, 'delete_tag');
      // Already gone on the server: converge locally, which is what the user asked for.
      if (error.kind !== 'tag_not_found') return fail(error);
    }
    set({ tags: get().tags.filter((t) => t.id !== id) });
    useCanvasStore.getState().updateEmbeddedTags((m) => removeEmbeddedTag(m, id));
    return { ok: true, value: undefined };
  },

  applyToggle: async (plan) => {
    const tag = get().tags.find((t) => t.id === plan.tagId);
    const operation: TagOperation = plan.action === 'link' ? 'link_tag' : 'unlink_tag';
    if (tag === undefined) return fail(new TagApiError('tag_not_found', operation, 'That tag no longer exists'));
    if (plan.snippetIds.length === 0) return { ok: true, value: undefined };
    const call = plan.action === 'link' ? tagApi.addToSnippet : tagApi.removeFromSnippet;
    const results = await Promise.allSettled(plan.snippetIds.map((sid) => call(sid, plan.tagId)));
    const done = plan.snippetIds.filter((_, i) => results[i].status === 'fulfilled');
    if (done.length > 0) {
      const linked = plan.action === 'link';
      useCanvasStore.getState().updateEmbeddedTags((m) => setEmbeddedTag(m, done, embedTag(tag), linked));
      const current = get().tags.find((t) => t.id === plan.tagId);
      if (current !== undefined) {
        const delta = linked ? done.length : -done.length;
        set({ tags: upsert(get().tags, { ...current, snippet_count: Math.max(0, current.snippet_count + delta) }) });
      }
    }
    const firstFailure = results.find((r): r is PromiseRejectedResult => r.status === 'rejected');
    return firstFailure === undefined ? { ok: true, value: undefined } : fail(toTagApiError(firstFailure.reason, operation));
  },

  createPreset: async (name, expression) => {
    const local = localNameError(name, 'create_preset', true);
    if (local) return fail(local);
    const check = checkPresetExpression(expression);
    if (!check.ok) {
      return fail(
        check.problem === 'empty_preset'
          ? new TagApiError('empty_preset', 'create_preset', 'Type an expression first')
          : new TagApiError('invalid_tag_expression', 'create_preset', check.error.message, null, check.error),
      );
    }
    try {
      const res = await tagPresetApi.create(name.trim(), check.normalized);
      set({ presets: upsert(get().presets, res.data) });
      return { ok: true, value: res.data };
    } catch (err) {
      return fail(toTagApiError(err, 'create_preset'));
    }
  },

  updatePreset: async (id, patch) => {
    const body: TagPresetUpdateBody = {};
    if (patch.name !== undefined) {
      const local = localNameError(patch.name, 'update_preset', true);
      if (local) return fail(local);
      body.name = patch.name.trim();
    }
    if (patch.expression !== undefined) {
      const check = checkPresetExpression(patch.expression);
      if (!check.ok) {
        return fail(
          check.problem === 'empty_preset'
            ? new TagApiError('empty_preset', 'update_preset', 'Type an expression first')
            : new TagApiError('invalid_tag_expression', 'update_preset', check.error.message, null, check.error),
        );
      }
      body.expression = check.normalized;
    }
    try {
      const res = await tagPresetApi.update(id, body);
      set({ presets: upsert(get().presets, res.data) });
      return { ok: true, value: res.data };
    } catch (err) {
      const error = toTagApiError(err, 'update_preset');
      if (error.kind === 'preset_not_found') set({ presets: get().presets.filter((p) => p.id !== id) });
      return fail(error);
    }
  },

  deletePreset: async (id) => {
    try {
      await tagPresetApi.delete(id);
    } catch (err) {
      const error = toTagApiError(err, 'delete_preset');
      if (error.kind !== 'preset_not_found') return fail(error);
    }
    set({ presets: get().presets.filter((p) => p.id !== id) });
    return { ok: true, value: undefined };
  },
}));
