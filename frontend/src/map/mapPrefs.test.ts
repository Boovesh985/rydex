import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { timeInForce, useMapPrefs } from './mapPrefs';
import { cityHour, isNightNow, nextDayNightSwitch } from './theme';

/** 18 October 2026, h:m in Chennai (UTC+5:30), whatever the clock of the machine running the tests */
const at = (h: number, m = 0, day = 18) => new Date(Date.UTC(2026, 9, day, h, m) - 330 * 60_000);

describe('day and night by the clock', () => {
  it('is day from 6 am to 6 pm and night from 6 pm to 6 am', () => {
    expect([at(5, 59), at(6), at(12), at(17, 59)].map((d) => isNightNow(d))).toEqual([true, false, false, false]);
    expect([at(18), at(23, 30), at(0), at(3)].map((d) => isNightNow(d))).toEqual([true, true, true, true]);
  });

  it("goes by Chennai's clock, not the viewer's", () => {
    // 00:30 UTC is 6 am in Chennai (day there), whatever time it is where the viewer sits
    expect(cityHour(Date.UTC(2026, 9, 18, 0, 30))).toBe(6);
    expect(isNightNow(Date.UTC(2026, 9, 18, 0, 30))).toBe(false);
    expect(isNightNow(Date.UTC(2026, 9, 18, 12, 30))).toBe(true); // 6 pm in Chennai
  });

  it('switches next at 6 am or 6 pm', () => {
    expect(nextDayNightSwitch(at(3, 20))).toBe(at(6).getTime());
    expect(nextDayNightSwitch(at(6))).toBe(at(18).getTime());
    expect(nextDayNightSwitch(at(14, 45))).toBe(at(18).getTime());
    expect(nextDayNightSwitch(at(21))).toBe(at(6, 0, 19).getTime());
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
