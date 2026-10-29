# Lyrics Canvas - Frontend Documentation

React 18 + TypeScript + Pixi.js v7 SPA for creating, organizing, and analyzing lyrics on an interactive infinite canvas.

**Tech Stack**: React 18, TypeScript, Vite 5, Pixi.js v7 (`@pixi/react`), Zustand, react-router-dom, Axios

## Module Documentation

| Module | Scope | Key Files |
|--------|-------|-----------|
| [Canvas Rendering](./frontend/canvas-rendering.md) | Pixi.js viewport, snippet cards, inline grid editor, context menu, grid background, group containers | `CanvasViewport`, `SnippetCard`, `SnippetContextMenu`, `InlineGridEditor`, `InfiniteGrid`, `GroupContainer` |
| [Beat Grid](./frontend/beat-grid.md) | Syllable grid display and editing, grid modes, CMU integration | `BeatGridDisplay`, `BeatGridEditor` |
| [Sidebars](./frontend/sidebars.md) | Rhyme sidebar, similarity analysis panel | `RhymeSidebar`, `SimilarityPanel` |
| [Toolbar & Tags](./frontend/toolbar.md) | Zoom, undo/redo, tool toggles, tag filter pills | `Toolbar`, `TagFilterBar` |
| [Modals](./frontend/modals.md) | Full-text search dialog, export format dialog | `SearchModal`, `ExportDialog` |
| [Pages & Routing](./frontend/pages-routing.md) | SPA routes, auth pages, canvas list, canvas editor | `App`, `LoginPage`, `RegisterPage`, `CanvasListPage`, `CanvasPage` |
| [Rhyme Sidebar](./frontend/rhyme-view.md) | Engine-backed rhyme search list with materialize-to-canvas | `rhymeStore`, `RhymeSidebar`, `QueryControls`, `CandidateList`, `CandidateRow`, `lib/rhyme/*` |
| [State Management](./frontend/state-management.md) | Zustand stores for auth, canvas data, UI state, rhyme sidebar | `authStore`, `canvasStore`, `uiStore`, `rhymeStore` |
| [List Mode](./features/list_mode.md) | Canvas page list view: last-edited order, tag filter, in-place editor, shared selection | `ListView`, `ListRow`, `GridPreview`, `lib/listMode/*`, `lib/canvas/visibility` |
| [Health Banner](./features/health_banner.md) | Backend health monitor, cluster alerts poller, status banner | `useStatusMonitoring`, `statusStore`, `StatusBanner`, `lib/health/*`, `lib/alerts/*`, `lib/statusBanner/*` |
| [API Client & Types](./frontend/api-client.md) | Axios HTTP client, TypeScript interfaces, constants | `client.ts`, `types/index.ts` |

## Architecture Summary

```
src/
  main.tsx                          Entry point
  App.tsx                           Router + ProtectedRoute
  types/index.ts                    Shared interfaces and constants
  types/rhyme.ts                    Rhyme-suggest API contract types + RhymeError
  api/client.ts                     Axios instance + endpoint modules (+ health interceptor)
  api/status.ts                     /api/health probe + /_status/alerts fetch (separate instance)
  hooks/
    useRhymeQuery.ts                Debounced rhyme re-query while the sidebar is open
    useStatusMonitoring.ts          Runs the health monitor + alerts poller (app root)
  lib/health/                       Health state machine, classification, monitor, client channel
  lib/alerts/                       Alert payload validation, fetch result folding, poller
  lib/statusBanner/                 Health + alerts -> ordered, dismissible banner items
  lib/canvas/
    visibility.ts                   Tag-filter visibility shared by canvas and list
  lib/listMode/
    order.ts                        Last-edited sort + frozen order while editing
    selection.ts                    Row click -> selection (plain / ctrl-cmd / shift range)
    viewMode.ts                     ViewMode + guarded localStorage persistence
  lib/rhyme/
    geometry.ts                     Point / viewport types, viewportCenter
    materializePosition.ts          Where a materialized candidate lands
    candidateList.ts                Result-list rows, shown range, More
    slotFit.ts                      Slot derivation + fit rule
    queryFromSelection.ts           Anchors from selection (minus chips) + typed text
    debounce.ts                     Debounced runner
    errors.ts                       axios -> RhymeError mapping
  stores/
    authStore.ts                    Auth state + JWT persistence
    canvasStore.ts                  Canvas data + undo/redo + t-SNE
    uiStore.ts                      Panel visibility + editing state + view mode
    rhymeStore.ts                   Rhyme sidebar session, query, materialize
    statusStore.ts                  Backend health, cluster alerts, dismissed banner keys
  components/
    canvas/
      CanvasViewport.tsx            Pixi Stage orchestrator
      SnippetCard.tsx               Individual snippet rendering
      gridConstants.ts              Beat-grid geometry constants
      gridDraw.ts                   Beat-grid drawing for SnippetCard
      gridCellText.ts               Cell label / tone / colours shared by gridDraw and GridPreview
      SnippetContextMenu.tsx        Right-click tag assignment menu
      InlineGridEditor.tsx          Inline beat grid editor (canvas overlay or in a list row)
      InfiniteGrid.tsx              Background grid
      GroupContainer.tsx            Group bounding boxes
    status/
      StatusBanner.tsx              Store-connected health/alerts banner
      StatusBannerView.tsx          Presentational banner cards
    list/
      ListView.tsx                  List mode: ordered, filtered rows; clicks; context menu
      ListRow.tsx                   Row: header, tags, GridPreview or the inline editor
      GridPreview.tsx               Read-only DOM beat grid
      useFrozenOrder.ts             Keeps row order while an editor is open
    rhyme/
      QueryControls.tsx             Search box, chips, k / score / slot / sources
      AnchorChips.tsx               Selected-snippet anchors with rhyme keys
      CandidateList.tsx             Status line, rows, More
      CandidateRow.tsx              One result with Materialize / Dismiss
      SyllableStrip.tsx             Compact grid_line strip
      AlignmentTable.tsx            Aligned tail vs anchor
      rhymeColors.ts                Provenance colours
      sidebarStyles.ts              Sidebar width + shared styles
    beatgrid/
      BeatGridDisplay.tsx           Read-only grid table
      BeatGridEditor.tsx            Full grid editing UI
    sidebar/
      RhymeSidebar.tsx              Engine-backed rhyme sidebar
      SimilarityPanel.tsx           Similarity results panel
    toolbar/
      Toolbar.tsx                   Top control bar
      TagFilterBar.tsx              Tag filter pills
    modals/
      SearchModal.tsx               Full-text search
      ExportDialog.tsx              Export format selection
    pages/
      LoginPage.tsx                 Login form
      RegisterPage.tsx              Registration form
      CanvasListPage.tsx            Canvas dashboard
      CanvasPage.tsx                Main editor orchestrator (canvas or list view)
```

## Data Flow

```
Components -> Zustand Actions -> Axios API -> Store Update -> Re-render
```

## Build

- Dev: `npm run dev` (Vite, port 5173, proxies `/api/*` to `localhost:8000`)
- Build: `npm run build` (`tsc && vite build`, ~887KB bundle)
- Type check: `npx tsc --noEmit`
- Tests: `npm test` (vitest 3.2.7, node environment, `src/**/*.test.ts`; alias `@` from `vitest.config.ts`)
