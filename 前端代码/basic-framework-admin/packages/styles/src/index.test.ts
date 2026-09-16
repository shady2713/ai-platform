import { describe, expect, it } from 'vitest';

// Static import executes the stylesheet wiring entry (@vben-core/design
// design tokens plus the global css files).
import * as styles from './index';

describe('styles entry', () => {
  it('loads the global stylesheet wiring and exports nothing', () => {
    expect(Object.keys(styles)).toEqual([]);
  });
});
