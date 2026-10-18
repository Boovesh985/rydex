import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { timeInForce, useMapPrefs } from './mapPrefs';
import { isNightNow, nextDayNightSwitch } from './theme';

const at = (h: number, m = 0) => new Date(2026, 9, 18, h, m);

describe('day and night by the clock', () => {
  it('is day from 6 am to 6 pm and night from 6 pm to 6 am', () => {
    expect([at(5, 59), at(6), at(12), at(17, 59)].map((d) => isNightNow(d))).toEqual([true, false, false, false]);
    expect([at(18), at(23, 30), at(0), at(3)].map((d) => isNightNow(d))).toEqual([true, true, true, true]);
  });

  it('switches next at 6 am or 6 pm', () => {
    expect(nextDayNightSwitch(at(3, 20))).toBe(at(6).getTime());
    expect(nextDayNightSwitch(at(6))).toBe(at(18).getTime());
    expect(nextDayNightSwitch(at(14, 45))).toBe(at(18).getTime());
    expect(nextDayNightSwitch(at(21))).toBe(new Date(2026, 9, 19, 6).getTime());
  });

  it('keeps a hand-picked look only until it runs out', () => {
    const until = at(18).getTime();
    expect(timeInForce('night', until, at(15).getTime())).toBe('night');
    expect(timeInForce('night', until, at(18, 1).getTime())).toBe('auto');
    // a choice saved before choices ran out has no end
    expect(timeInForce('day', undefined, at(22).getTime())).toBe('auto');
    expect(timeInForce('auto', null, at(22).getTime())).toBe('auto');
  });
});

describe('switching by hand', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(at(14));
    useMapPrefs.setState({ time: 'auto', timeUntil: null });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('holds night until 6 pm, and back to day is the clock again', () => {
    useMapPrefs.getState().setNight(true);
    expect(useMapPrefs.getState()).toMatchObject({ time: 'night', timeUntil: at(18).getTime() });

    useMapPrefs.getState().setNight(false);
    expect(useMapPrefs.getState()).toMatchObject({ time: 'auto', timeUntil: null });
  });
});
