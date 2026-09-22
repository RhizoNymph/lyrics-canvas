# Rhyme View

Ephemeral rhyme-candidate layer on the canvas. Select a snippet (or type a phrase) and engine-scored
candidates appear around the anchor as beat-grid cards; promote one to make it a real snippet, dismiss the
rest, exit and nothing else has changed. Feature contract: [`docs/features/rhyme_view.md`](../features/rhyme_view.md).

## Files

| File | Role |
|------|------|
| `src/types/rhyme.ts` | TS mirrors of the API contract (`RhymeUnit`, `RhymeCandidate`, `RhymeSuggestRequest/Response`, `AnchorRef`, `Slot`, `DerivedFrom`, `DialectMap`, `ScoreWeights`), constants (`RHYME_PAGE_SIZE = 40`, `STRESS_PATTERN`), and the `RhymeError` class |
| `src/lib/rhyme/errors.ts` | `toRhymeError(err, operation)` maps axios/unknown failures to `RhymeError` (`network`, `not_found`, `invalid_request`, `server`, `unknown`) |
| `src/lib/rhyme/radialLayout.ts` | `stableHash`, `placeCandidate`, `layoutCandidates`, `anchorCenter` |
| `src/lib/rhyme/slotFit.ts` | `slotFromGridLine`, `slotFromSnippet`, `lastNonEmptyLineIndex`, `fits`, `parseManualSlot` |
| `src/lib/rhyme/queryFromSelection.ts` | `selectedSnippets`, `anchorsFromSelection` |
| `src/lib/rhyme/candidateGeometry.ts` | `candidateCardSize`, `candidateTopLeft` (pure; no Pixi) |
| `src/lib/rhyme/debounce.ts` | `createDebouncedRunner(fn, ms)` with `schedule` / `runNow` / `cancel` |
| `src/stores/rhymeStore.ts` | `useRhymeStore`: view mode, params, candidates, positions, dismissed, hover/pin, loading/error |
| `src/hooks/useRhymeQuery.ts` | Runs `runQuery` immediately on enter and 150 ms after selection/param changes |
| `src/components/canvas/gridConstants.ts` | `CELL_WIDTH`, `CELL_HEIGHT`, `PADDING`, `HEADER_HEIGHT`, `COUNT_COLUMN_WIDTH` |
| `src/components/canvas/gridDraw.ts` | Shared Pixi beat-grid drawing (see [canvas-rendering](./canvas-rendering.md#griddraw)) |
| `src/components/rhyme/CandidateCard.tsx` | Pixi card for one candidate |
| `src/components/rhyme/RhymeLayer.tsx` | Renders the current page window of candidate cards inside the Pixi container |
| `src/components/rhyme/RhymePanel.tsx` | HTML overlay with the controls |
| `src/components/rhyme/CandidateTooltip.tsx` | HTML overlay for the hovered/pinned candidate |
| `src/components/rhyme/rhymeColors.ts` | Provenance colours/labels, `NON_FIT_ALPHA`, `alignmentColor` |
| `src/components/rhyme/dashedRect.ts` | `drawDashedRoundedRect` (Pixi v7 has no dashed line style) |

Tests (vitest, node environment): `src/lib/rhyme/*.test.ts`, `src/stores/rhymeStore.test.ts`. Pixi components
are not unit-tested; the store test mocks `@/api/client`.

## Store (`useRhymeStore`)

| Field | Type | Notes |
|-------|------|-------|
| `active` | boolean | Toolbar toggle |
| `typedAnchor` | string | Panel phrase; becomes a `{kind: 'text'}` anchor |
| `k` | `1 \| 2 \| 3 \| 4` | Default 2 |
| `minScore` | number | Default 0.5, clamped to [0, 1] |
| `slot` | `Slot \| null` | Derived from the first selected snippet's last non-empty line in `from-selection` mode; user-entered in `manual` mode |
| `slotMode` | `'from-selection' \| 'manual'` | |
| `sources` | `{ notes, dictionary }` | Both true by default |
| `anchors` | `RhymeUnit[]` | Echoed back by the server with phonetics |
| `candidates` | `RhymeCandidate[]` | All loaded pages, dismissed ids removed |
| `total`, `offset` | number | Server total; offset of the last loaded page |
| `positions` | `Map<id, {x, y}>` | World-coordinate card **centres**; never recomputed for known ids |
| `dismissed` | `Set<id>` | Sent as `exclude_ids`; also receives promoted ids |
| `hoveredId`, `pinnedId` | `string \| null` | Tooltip target is `pinnedId ?? hoveredId` |
| `viewSize` | `{ width, height }` | Canvas element size (set by `CanvasViewport`) for the typed-anchor fallback centre |
| `loading`, `error` | boolean, `RhymeError \| null` | Errors are stored, never thrown |

Actions: `enter`, `exit`, `setTypedAnchor`, `setK`, `setMinScore`, `setSlot`, `setSlotMode`, `toggleSource`,
`setViewSize`, `runQuery`, `loadMore`, `dismiss`, `promote(id, at?)`, `setHovered`, `setPinned`.

### runQuery

1. Returns early when inactive or no canvas is loaded.
2. Anchors = `anchorsFromSelection(selectedSnippetIds, snippets, typedAnchor)`: selected snippets with non-blank
   text (id order, `line_index: null`) followed by the trimmed typed phrase.
3. With no anchors or no enabled source: candidates/anchors/total cleared, no request.
4. Slot: `slotFromSnippet(firstSelected, null)` in `from-selection` mode, else the manual slot.
5. Request: `{canvas_id, anchors, context: [], slot, k, min_score, sources, include_rare: false,
   exclude_ids: [...dismissed], limit: 40, offset: 0, weights: null, dialect: null}`.
6. Response: dismissed ids filtered out; `positions = layoutCandidates(candidates, center, positions)` where
   `center` is the centroid of the selected snippets or the viewport centre.
7. A monotonic sequence number drops responses that resolve after a newer query (or after `exit`).

`loadMore` requests `offset + 40` with the same parameters, appends unseen candidates, and places only the
new ids. It is a no-op while loading or when `offset + 40 >= total`.

### promote

`promote(id, at?)` calls `canvasStore.addSnippetFromText(text, x, y, derived_from)` with `at` (world
top-left of the drop) or the card's own top-left, then dismisses the id. `derived_from` is
`{provenance, source_unit_id: id, score, match_depth, k, anchor_unit_id: best_anchor_id, anchor_text}`.
Failures become `RhymeError` with `operation: 'promote'`; the candidate stays.

### exit

Clears candidates, anchors, positions, dismissed, typed anchor, hover/pin, total/offset, loading and error, and
bumps the query sequence so in-flight responses are ignored. `k`, `minScore`, `slotMode`, `sources` persist for
the session. Canvas snippets are never touched.

## Layout

`lib/rhyme/radialLayout.ts`, all in world coordinates:

```
distance = base (320) + (1 - clamp(score, 0, 1)) * spread (480) + jitter_d(id) * 70
angle    = (fnv1a(rhyme_key.join(' ')) mod 360)° + jitter_a(id) * 0.22 rad
```

Jitter is a deterministic function of the unit id, so a rhyme family forms a wedge whose members do not
overlap, and the same inputs always give the same output. Non-finite scores count as 0. `layoutCandidates`
returns a new map: every existing entry unchanged plus fresh entries for unknown ids.

## Rendering

`RhymeLayer` is mounted inside `CanvasViewport`'s Pixi `<Container>` after the snippet cards and renders
`candidates.slice(offset, offset + 40)` — the current page window, so at most 40 cards are on the canvas.

`CandidateCard` uses the same cell geometry as `SnippetCard` (`gridConstants`, `gridDraw`) with a single grid
row from `candidate.grid_line`:

| Element | Style |
|---------|-------|
| Header | Provenance colour at 80%: note `#0ea5e9` (sky), dictionary `#14b8a6` (teal), generated `#71717a` (grey); label `NOTE` / `DICT` / `TEXT`, score right-aligned |
| Border | Dashed 1.5px in the header colour while `ephemeral`; white when hovered or pinned |
| Body | `0x1c1c38` at 92%, shadow at 20% |
| Alpha | `0.45` when `!fits_slot` |
| Size | `max(150, 2*PADDING + cells*CELL_WIDTH + 28)` × `HEADER_HEIGHT + 2*PADDING + CELL_HEIGHT`, centred on the layout point |

Cards scale with canvas zoom only (no per-card scale).

## Interactions

| Input | Behaviour |
|-------|-----------|
| Toolbar "Rhyme View" | `enter()` / `exit()`; entering with a selection queries immediately |
| Select snippets / type phrase / change k, min score, slot, sources | Re-query after 150 ms (`useRhymeQuery`) |
| Hover card | `setHovered(id)`; tooltip shows. Hover-out clears after 250 ms so the pointer can reach the tooltip |
| Click card (< 4 px movement) | Toggle `pinnedId` |
| Drag card ≥ 24 world units and release | `promote(id, dropTopLeft)`; smaller drags snap back |
| Tooltip Promote / Dismiss | `promote(id)` at the card's position / `dismiss(id)` |
| Panel More | `loadMore()`; disabled while loading or when everything is loaded |
| Panel Exit | `exit()` |
| Double-click a card | Ignored by the viewport's create-snippet path (`markSnippetInteraction` timestamp guard) |

The panel validates manual slots with `parseManualSlot`: stress must match `^[012]+$` and the count must equal
its length; invalid input sends `slot: null` and shows the reason.

The tooltip shows text, `score.toFixed(2)`, match depth, source, provenance, stress, and the alignment as three
rows (candidate phones, anchor phones, per-syllable total) coloured by `alignmentColor(total)` from red (0) to
green (1), displayed left-to-right (the API returns right-to-left).

## Data flow

```
Toolbar enter -> useRhymeQuery runNow -> rhymeStore.runQuery
   -> anchorsFromSelection + slotFromSnippet -> nlpApi.rhymeSuggest
   <- anchors + candidates -> layoutCandidates -> RhymeLayer/CandidateCard
hover/click -> hoveredId/pinnedId -> CandidateTooltip
promote -> canvasStore.addSnippetFromText -> snippetApi.create {raw_text, position, derived_from}
   -> new Snippet in canvasStore (undo snapshot) ; candidate dismissed
exit -> store cleared; snippets untouched
```
