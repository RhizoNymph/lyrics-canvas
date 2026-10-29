import type { HealthObservation } from './types';

export type ObservationListener = (observation: HealthObservation) => void;

/**
 * One-way channel from the API client (producer) to the health monitor (consumer), so the client
 * never imports the monitor or the store.
 */
export interface ObservationChannel {
  publish(observation: HealthObservation): void;
  /** Returns the unsubscribe function. */
  subscribe(listener: ObservationListener): () => void;
}

export function createObservationChannel(): ObservationChannel {
  const listeners = new Set<ObservationListener>();
  return {
    publish(observation) {
      for (const listener of listeners) listener(observation);
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

/** Observations from ordinary `/api/*` calls made through `api/client.ts`. */
export const apiHealthChannel: ObservationChannel = createObservationChannel();
