# Lyrics Canvas - Frontend Documentation

React 18 + TypeScript + Pixi.js v7 SPA for creating, organizing, and analyzing lyrics on an interactive infinite canvas.

**Tech Stack**: React 18, TypeScript, Vite 5, Pixi.js v7 (`@pixi/react`), Zustand, react-router-dom, Axios

## Module Documentation

| Module | Scope | Key Files |
|--------|-------|-----------|
| [Canvas Rendering](./frontend/canvas-rendering.md) | Pixi.js viewport, snippet cards, inline grid editor, context menu, grid background, group containers | `CanvasViewport`, `SnippetCard`, `SnippetContextMenu`, `InlineGridEditor`, `InfiniteGrid`, `GroupContainer` |
| [Beat Grid](./frontend/beat-grid.md) | Syllable grid display and editing, grid modes, CMU integration | `BeatGridDisplay`, `BeatGridEditor` |
| [Sidebars](./frontend/sidebars.md) | Rhyme lookup panel, similarity analysis panel | `RhymeSidebar`, `SimilarityPanel` |
| [Toolbar & Tags](./frontend/toolbar.md) | Zoom, undo/redo, tool toggles, tag filter pills | `Toolbar`, `TagFilterBar` |
| [Modals](./frontend/modals.md) | Full-text search dialog, export format dialog | `SearchModal`, `ExportDialog` |
| [Pages & Routing](./frontend/pages-routing.md) | SPA routes, auth pages, canvas list, canvas editor | `App`, `LoginPage`, `RegisterPage`, `CanvasListPage`, `CanvasPage` |
| [State Management](./frontend/state-management.md) | Zustand stores for auth, canvas data, UI state | `authStore`, `canvasStore`, `uiStore` |
| [API Client & Types](./frontend/api-client.md) | Axios HTTP client, TypeScript interfaces, constants | `client.ts`, `types/index.ts` |

## Architecture Summary

```
src/
  main.tsx                          Entry point
  App.tsx                           Router + ProtectedRoute
  types/index.ts                    Shared interfaces and constants
  api/client.ts                     Axios instance + endpoint modules
  stores/
    authStore.ts                    Auth state + JWT persistence
    canvasStore.ts                  Canvas data + undo/redo + t-SNE
    uiStore.ts                      Panel visibility + editing state
  components/
    canvas/
      CanvasViewport.tsx            Pixi Stage orchestrator
      SnippetCard.tsx               Individual snippet rendering
      SnippetContextMenu.tsx        Right-click tag assignment menu
      InlineGridEditor.tsx          Inline beat grid editing overlay
      InfiniteGrid.tsx              Background grid
      GroupContainer.tsx            Group bounding boxes
    beatgrid/
      BeatGridDisplay.tsx           Read-only grid table
      BeatGridEditor.tsx            Full grid editing UI
    sidebar/
      RhymeSidebar.tsx              Rhyme lookup panel
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
      CanvasPage.tsx                Main editor orchestrator
```

## Data Flow

```
Components -> Zustand Actions -> Axios API -> Store Update -> Re-render
```

## Build

- Dev: `npm run dev` (Vite, port 5173, proxies `/api/*` to `localhost:8000`)
- Build: `npx vite build` (852KB bundle)
- Type check: `npx tsc --noEmit`
