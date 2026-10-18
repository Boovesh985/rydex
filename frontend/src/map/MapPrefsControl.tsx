import { Moon, Sun, SunMoon } from 'lucide-react';
import type { IControl, Map as MlMap } from 'maplibre-gl';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useMapPrefs, useTimeOfDay } from './mapPrefs';
import { cityHour, DAY_STARTS, NIGHT_STARTS } from './theme';
import { TRAFFIC_COLORS } from './traffic';

const hour = (h: number) => `${h % 12 || 12} ${h < 12 ? 'am' : 'pm'}`;

/**
 * The viewer's map switches, in the map's own control corner: traffic, and day or night (by the
 * clock: day 6 am to 6 pm; a switch by hand holds until the clock's next change).
 */
export function MapPrefsControl({ map }: { map: MlMap }) {
  const [box] = useState(() => {
    const el = document.createElement('div');
    el.className = 'maplibregl-ctrl maplibregl-ctrl-group rx-prefs';
    return el;
  });
  const traffic = useMapPrefs((s) => s.traffic);
  const { time, night } = useTimeOfDay();
  const toggleTraffic = useMapPrefs((s) => s.toggleTraffic);
  const setNight = useMapPrefs((s) => s.setNight);
  const until = useMapPrefs((s) => s.timeUntil);

  useEffect(() => {
    const control: IControl = { onAdd: () => box, onRemove: () => box.remove() };
    map.addControl(control, 'bottom-right');
    return () => {
      if (map.hasControl(control)) map.removeControl(control);
    };
  }, [map, box]);

  // the icon shows the look now (sun and moon together while the clock decides)
  const TimeIcon = time === 'auto' ? SunMoon : night ? Moon : Sun;
  const now = night ? 'Night' : 'Day';
  const label = `${time === 'auto' ? `${now} by the clock (day ${hour(DAY_STARTS)} to ${hour(NIGHT_STARTS)})` : `${now} until ${hour(cityHour(until ?? 0))}`}. Switch to ${night ? 'day' : 'night'}`;
  return createPortal(
    <>
      <button type="button" aria-pressed={traffic} title="Traffic" aria-label="Traffic" onClick={toggleTraffic} className={traffic ? 'rx-on' : ''}>
        <span className="rx-traffic-icon" aria-hidden>
          {Object.values(TRAFFIC_COLORS).map((c) => (
            <i key={c} style={{ background: c }} />
          ))}
        </span>
      </button>
      <button type="button" title={label} aria-label={label} aria-pressed={night} onClick={() => setNight(!night)}>
        <TimeIcon className="mx-auto size-4" aria-hidden />
      </button>
    </>,
    box,
  );
}
