import { describe, expect, it } from 'vitest';

import { ACTION_ICON } from './icons';

describe('table action icons', () => {
  it('maps every action kind to a lucide icon name', () => {
    expect(ACTION_ICON).toEqual({
      ADD: 'lucide:plus',
      AUDIT: 'lucide:file-check',
      BOOK: 'lucide:book',
      CLOSE: 'lucide:x',
      COPY: 'lucide:copy',
      DELETE: 'lucide:trash-2',
      DOWNLOAD: 'lucide:download',
      EDIT: 'lucide:edit',
      FILTER: 'lucide:filter',
      MORE: 'lucide:ellipsis-vertical',
      REFRESH: 'lucide:refresh-cw',
      SEARCH: 'lucide:search',
      UPLOAD: 'lucide:upload',
      VIEW: 'lucide:eye',
    });
  });

  it('keeps every value in the lucide icon set', () => {
    for (const icon of Object.values(ACTION_ICON)) {
      expect(icon.startsWith('lucide:')).toBe(true);
    }
  });
});
