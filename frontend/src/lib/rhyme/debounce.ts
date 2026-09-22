export interface DebouncedRunner {
  /** Run `fn` after `ms` of quiet; repeated calls restart the timer. */
  schedule(): void;
  /** Run `fn` right away and drop any pending run. */
  runNow(): void;
  cancel(): void;
}

export function createDebouncedRunner(fn: () => void, ms: number): DebouncedRunner {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const cancel = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };
  return {
    schedule() {
      cancel();
      timer = setTimeout(() => {
        timer = null;
        fn();
      }, ms);
    },
    runNow() {
      cancel();
      fn();
    },
    cancel,
  };
}
