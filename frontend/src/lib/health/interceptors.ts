import type { AxiosInstance } from 'axios';
import { passiveObservationFromError } from './classify';
import type { HealthObservation } from './types';

/**
 * Report the outcome of every request on `instance` as a passive health observation. Responses and
 * errors pass through unchanged, so callers see exactly what they saw before. Register it after the
 * client's other response interceptors.
 */
export function installHealthInterceptors(
  instance: AxiosInstance,
  publish: (observation: HealthObservation) => void,
  isOnline: () => boolean
): void {
  instance.interceptors.response.use(
    (response) => {
      publish({ kind: 'ok' });
      return response;
    },
    (err: unknown) => {
      const observation = passiveObservationFromError(err, isOnline());
      if (observation) publish(observation);
      return Promise.reject(err);
    }
  );
}
