import { useMemo } from 'react';
import { buildBannerModel, visibleItems } from '@/lib/statusBanner/items';
import { useStatusStore } from '@/stores/statusStore';
import { StatusBannerView } from './StatusBannerView';

/** Server health and cluster alerts, from `statusStore`. Mount once at the app root. */
export default function StatusBanner() {
  const health = useStatusStore((s) => s.health);
  const alerts = useStatusStore((s) => s.alerts);
  const dismissed = useStatusStore((s) => s.dismissed);
  const dismiss = useStatusStore((s) => s.dismiss);

  const model = useMemo(() => buildBannerModel(health, alerts), [health, alerts]);
  const items = useMemo(() => visibleItems(model, dismissed), [model, dismissed]);

  return <StatusBannerView items={items} alertsStale={model.alertsStale} onDismiss={dismiss} />;
}
