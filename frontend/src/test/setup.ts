import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach } from 'vitest';

// findBy*/waitFor wait up to 1 s by default: with every test file running at once (and on CI) a
// screen's first render can take longer than that, which made the first test of a file flaky
configure({ asyncUtilTimeout: 4000 });

afterEach(() => {
  cleanup();
  localStorage.clear();
});
