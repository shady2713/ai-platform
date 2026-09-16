import { describe, expect, it } from 'vitest';

import {
  expectedTypecheckCommand,
  parseWorkspacePatterns,
  typecheckContractFailures,
} from './check-typecheck-contract.mjs';

describe('workspace typecheck contract', () => {
  it('reads only workspace package patterns', () => {
    expect(
      parseWorkspacePatterns(
        `packages:\n  - packages/*\n  - 'apps/*'\n\noverrides:\n  vue: latest\n`,
      ),
    ).toEqual(['packages/*', 'apps/*']);
  });

  it('uses vue-tsc only when a package owns Vue source files', () => {
    expect(expectedTypecheckCommand(false)).toBe('tsc --noEmit');
    expect(expectedTypecheckCommand(true)).toBe(
      'vue-tsc --noEmit --skipLibCheck',
    );
  });

  it('rejects missing and ineffective typecheck registrations', () => {
    expect(
      typecheckContractFailures([
        { hasVueSource: false, name: 'valid-ts', typecheck: 'tsc --noEmit' },
        {
          hasVueSource: true,
          name: 'valid-vue',
          typecheck: 'vue-tsc --noEmit --skipLibCheck',
        },
      ]),
    ).toEqual([]);
    expect(
      typecheckContractFailures([
        { hasVueSource: false, name: 'missing', typecheck: undefined },
        { hasVueSource: true, name: 'bypass', typecheck: 'echo skipped' },
      ]),
    ).toHaveLength(2);
  });
});
