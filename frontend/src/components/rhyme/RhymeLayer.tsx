import { useRhymeStore } from '@/stores/rhymeStore';
import { RHYME_PAGE_SIZE } from '@/types/rhyme';
import type { RhymeCandidate } from '@/types/rhyme';
import CandidateCard from './CandidateCard';

interface Props {
  viewport: { x: number; y: number; zoom: number };
}

/** The current page window: at most RHYME_PAGE_SIZE candidates starting at `offset`. */
export function visibleCandidates(candidates: ReadonlyArray<RhymeCandidate>, offset: number): RhymeCandidate[] {
  return candidates.slice(offset, offset + RHYME_PAGE_SIZE);
}

/** Renders candidate cards inside CanvasViewport's Pixi Container, after the snippets. */
export default function RhymeLayer({ viewport }: Props) {
  const { active, candidates, positions, offset } = useRhymeStore();
  if (!active) return null;
  return (
    <>
      {visibleCandidates(candidates, offset).map((c) => {
        const center = positions.get(c.id);
        if (!center) return null;
        return <CandidateCard key={c.id} candidate={c} center={center} viewport={viewport} />;
      })}
    </>
  );
}
