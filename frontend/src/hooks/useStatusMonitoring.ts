import { useEffect } from 'react';
import { fetchClusterAlerts, probeHealth } from '@/api/status';
import { applyAlertFetch } from '@/lib/alerts/parse';
import { createAlertsPoller } from '@/lib/alerts/poller';
import { apiHealthChannel } from '@/lib/health/channel';
import { createHealthMonitor } from '@/lib/health/monitor';
import { useStatusStore } from '@/stores/statusStore';

/**
 * Run the backend health monitor and the cluster alerts poller for the app's lifetime, writing into
 * `statusStore`. Polling pauses while the tab is hidden and resumes (with an immediate check) when it
 * is shown again or the browser comes back online. Mount once, at the app root.
 */
export function useStatusMonitoring(): void {
  useEffect(() => {
    const store = useStatusStore.getState();
    const monitor = createHealthMonitor({ probe: probeHealth, now: Date.now, onChange: store.setHealth });
    const poller = createAlertsPoller({
      fetch: fetchClusterAlerts,
      onResult: (result) => {
        const { alerts, setAlerts } = useStatusStore.getState();
        setAlerts(applyAlertFetch(alerts, result, Date.now()));
      },
    });

    const unsubscribe = apiHealthChannel.subscribe((observation) => monitor.observe(observation, 'passive'));

    const onVisibility = () => {
      if (document.hidden) {
        monitor.pause();
        poller.pause();
      } else {
        monitor.resume();
        poller.resume();
      }
    };
    const onOnline = () => {
      monitor.probeNow();
      poller.pollNow();
    };
    const onOffline = () => monitor.browserOffline();

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);

    monitor.start();
    poller.start();
    if (document.hidden) {
      monitor.pause();
      poller.pause();
    }
    if (!navigator.onLine) monitor.browserOffline();

    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('online', onOnline);
      window.removeEventListener('offline', onOffline);
      unsubscribe();
      monitor.stop();
      poller.stop();
    };
  }, []);
}
