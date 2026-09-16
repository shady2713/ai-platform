import { nextTick, ref } from 'vue';

import { describe, expect, it } from 'vitest';

import { usePagination } from './use-pagination';

describe('usePagination', () => {
  it('paginates the current page and reports the total', () => {
    const pagination = usePagination(ref([1, 2, 3, 4, 5]), 2);

    expect(pagination.total.value).toBe(5);
    expect(pagination.paginationList.value).toEqual([1, 2]);
    pagination.setCurrentPage(3);
    expect(pagination.paginationList.value).toEqual([5]);
  });

  it('rejects page numbers outside the available range', () => {
    const pagination = usePagination(ref([1, 2]), 1);

    expect(() => pagination.setCurrentPage(0)).toThrow('Invalid page number');
    expect(() => pagination.setCurrentPage(3)).toThrow('Invalid page number');
  });

  it('resets the page when page size or list length changes', async () => {
    const list = ref([1, 2, 3, 4]);
    const pagination = usePagination(list, 2);
    pagination.setCurrentPage(2);

    pagination.setPageSize(3);
    expect(pagination.currentPage.value).toBe(1);
    pagination.setCurrentPage(2);
    list.value = [1];
    await nextTick();
    expect(pagination.currentPage.value).toBe(1);
  });

  it('handles an empty list and rejects invalid page sizes', () => {
    const pagination = usePagination(ref<number[]>([]), 10);

    pagination.setCurrentPage(1);
    expect(pagination.paginationList.value).toEqual([]);
    expect(() => pagination.setPageSize(0)).toThrow(
      'Page size must be positive',
    );
  });
});
