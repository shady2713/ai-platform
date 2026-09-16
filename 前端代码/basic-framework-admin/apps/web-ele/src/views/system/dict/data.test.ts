import { describe, expect, it, vi } from 'vitest';

import { getSimpleDictTypeList } from '#/api/system/dict/type';

import {
  useDataFormSchema,
  useDataGridColumns,
  useDataGridFormSchema,
  useTypeFormSchema,
  useTypeGridColumns,
  useTypeGridFormSchema,
} from './data';

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0 },
  DICT_TYPE: { COMMON_STATUS: 'common_status' },
}));

vi.mock('@vben/hooks', () => ({ getDictOptions: vi.fn(() => []) }));

vi.mock('#/adapter/form', async () => {
  const { z } = await import('@vben/common-ui');
  return { z };
});

vi.mock('#/api/system/dict/type', () => ({
  getSimpleDictTypeList: vi.fn(),
}));

type ComponentPropsFactory = (values: Record<string, unknown>) => {
  api?: unknown;
  disabled?: boolean;
  options?: Array<{ label: string; value: string }>;
};

describe('system dictionary schemas', () => {
  it('locks immutable dictionary identifiers after creation', () => {
    const typeField = useTypeFormSchema().find(
      ({ fieldName }) => fieldName === 'type',
    );
    const typeProps = typeField?.componentProps as ComponentPropsFactory;
    expect(typeProps({}).disabled).toBe(false);
    expect(typeProps({ id: 1 }).disabled).toBe(true);

    const dictTypeField = useDataFormSchema().find(
      ({ fieldName }) => fieldName === 'dictType',
    );
    const dictTypeProps =
      dictTypeField?.componentProps as ComponentPropsFactory;
    expect(dictTypeProps({}).api).toBe(getSimpleDictTypeList);
    expect(dictTypeProps({ id: 1 }).disabled).toBe(true);
  });

  it('keeps form, search and table contracts aligned', () => {
    const dataSchema = useDataFormSchema();
    const color = dataSchema.find(({ fieldName }) => fieldName === 'colorType');
    const colorOptions = (
      color?.componentProps as { options: Array<{ value: string }> }
    ).options;
    expect(colorOptions.map(({ value }) => value)).toEqual(
      expect.arrayContaining(['', 'success', 'warning', 'error', 'blue']),
    );

    expect(useTypeGridFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'name',
      'type',
      'status',
    ]);
    expect(useDataGridFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'label',
      'status',
    ]);
    expect(
      useTypeGridColumns()?.some(({ field }) => field === 'createTime'),
    ).toBe(true);
    expect(
      useDataGridColumns()?.some(({ field }) => field === 'cssClass'),
    ).toBe(true);
  });
});
