import { useListOrderStore } from '@/stores/listOrderStore';
import { LIST_SORTS, LIST_SORT_LABELS, parseListSort } from '@/lib/listMode';
import type { ListSort } from '@/lib/listMode';

/** The list view's sort menu, bound to listOrderStore (remembered per device). */
export default function ListSortMenu() {
  const sort = useListOrderStore((s) => s.sort);
  const setSort = useListOrderStore((s) => s.setSort);
  return <ListSortMenuView sort={sort} onChange={setSort} />;
}

export function ListSortMenuView({ sort, onChange }: { sort: ListSort; onChange: (sort: ListSort) => void }) {
  return (
    <div
      onClick={(e) => e.stopPropagation()}
      style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8, marginBottom: 12 }}
    >
      <label htmlFor="list-sort" style={{ color: '#8a8aaa', fontSize: 12 }}>
        Sort
      </label>
      <select
        id="list-sort"
        value={sort}
        onChange={(e) => {
          const next = parseListSort(e.target.value);
          if (next !== null) onChange(next);
        }}
        style={{
          background: '#1c1c38',
          color: '#e0e0f0',
          border: '1px solid #3a3a5a',
          borderRadius: 6,
          padding: '4px 8px',
          fontSize: 12,
        }}
      >
        {LIST_SORTS.map((s) => (
          <option key={s} value={s}>
            {LIST_SORT_LABELS[s]}
          </option>
        ))}
      </select>
    </div>
  );
}
