export { EMPTY_SEARCH, isSearchActive, matchesSearch, parseSearchQuery, searchableText } from './match';
export type { SearchQuery } from './match';
export { highlightSegments } from './highlight';
export type { HighlightSegment } from './highlight';
export {
  SearchError,
  MAX_GLOBAL_QUERY_LENGTH,
  checkGlobalQuery,
  describeSearchError,
  toSearchError,
} from './errors';
export type { GlobalQueryCheck, SearchErrorKind } from './errors';
export {
  FOCUS_PARAM,
  HIDDEN_BY_TAG_FILTER,
  canvasPath,
  SNIPPET_NOT_ON_CANVAS,
  planSnippetFocus,
  runSnippetFocus,
  snippetFocusParam,
  viewportCentredOn,
} from './focus';
export type { FocusPlan, FocusPorts } from './focus';
export { filterCountLabels } from './counts';
export type { FilterCounts } from './counts';
export { GLOBAL_SEARCH_DEBOUNCE_MS, createGlobalSearch } from './globalSearch';
export type { GlobalSearchController, GlobalSearchDeps, GlobalSearchView } from './globalSearch';
