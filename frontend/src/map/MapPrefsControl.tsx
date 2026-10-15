import { Moon, Sun, SunMoon } from 'lucide-react';
import type { IControl, Map as MlMap } from 'maplibre-gl';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { nextTime, useMapPrefs } from './mapPrefs';
import { TRAFFIC_COLORS } from './traffic';

const TIME_LABEL = { auto: 'Day and night: automatic', day: 'Day and night: always day', night: 'Day and night: always night' } as const;

/** The viewer's map switches, in the map's own control corner: traffic, and day/night/auto. */
export function MapPrefsControl({ map }: { map: MlMap }) {
  const [box] = useState(() => {
    const el = document.createElement('div');
    el.className = 'maplibregl-ctrl maplibregl-ctrl-group rx-prefs';
    return el;
  });
  const traffic = useMapPrefs((s) => s.traffic);
  const time = useMapPrefs((s) => s.time);
  const toggleTraffic = useMapPrefs((s) => s.toggleTraffic);
  const setTime = useMapPrefs((s) => s.setTime);

  useEffect(() => {
    const control: IControl = { onAdd: () => box, onRemove: () => box.remove() };
    map.addControl(control, 'bottom-right');
    return () => {
      if (map.hasControl(control)) map.removeControl(control);
    };
  }, [map, box]);

  const TimeIcon = time === 'auto' ? SunMoon : time === 'day' ? Sun : Moon;
  return createPortal(
    <>
      <button type="button" aria-pressed={traffic} title="Traffic" aria-label="Traffic" onClick={toggleTraffic} className={traffic ? 'rx-on' : ''}>
        <span className="rx-traffic-icon" aria-hidden>
          {Object.values(TRAFFIC_COLORS).map((c) => (
            <i key={c} style={{ background: c }} />
          ))}
        </span>
      </button>
      <button type="button" title={TIME_LABEL[time]} aria-label={TIME_LABEL[time]} onClick={() => setTime(nextTime(time))}>
        <TimeIcon className="mx-auto size-4" aria-hidden />
      </button>
    </>,
    box,
  );
}
