import { describe, expect, it, vi } from 'vitest';

import { useFormSchema, useGridFormSchema } from './data';

const dictOptions = [
  { label: '数据库', value: 1 },
  { label: '本地磁盘', value: 10 },
  { label: 'FTP 服务器', value: 11 },
  { label: 'SFTP 服务器', value: 12 },
  { label: 'S3 对象存储', value: 20 },
];

vi.mock('@vben/constants', () => ({
  DICT_TYPE: {
    INFRA_BOOLEAN_STRING: 'infra_boolean_string',
    INFRA_FILE_STORAGE: 'infra_file_storage',
  },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => dictOptions),
}));

vi.mock('#/utils', () => ({
  getRangePickerDefaultProps: () => ({}),
}));

type FileConfigFormValues = {
  id?: number;
  storage?: number;
};

type FileConfigSchemaField = {
  component?: string;
  componentProps?: {
    options?: unknown;
  };
  dependencies?: {
    rules?: (values: FileConfigFormValues) => {
      safeParse: (value: unknown) => { success: boolean };
    };
  };
  fieldName?: string;
};

type StorageOption = {
  label: string;
  value: number;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isSchemaField(value: unknown): value is FileConfigSchemaField {
  return isRecord(value);
}

function isStorageOption(value: unknown): value is StorageOption {
  return (
    isRecord(value) &&
    typeof value.label === 'string' &&
    typeof value.value === 'number'
  );
}

function getStorageOptions(schema: unknown[]): StorageOption[] {
  const field = schema.find(
    (item): item is FileConfigSchemaField =>
      isSchemaField(item) && item.fieldName === 'storage',
  );
  const options = field?.componentProps?.options;
  return Array.isArray(options)
    ? options.filter((option) => isStorageOption(option))
    : [];
}

function getField(
  schema: unknown[],
  fieldName: string,
): FileConfigSchemaField | undefined {
  return schema.find(
    (item): item is FileConfigSchemaField =>
      isSchemaField(item) && item.fieldName === fieldName,
  );
}

describe('fileConfig storage options', () => {
  it('编辑表单只展示本地存储和对象存储', () => {
    expect(
      getStorageOptions(useFormSchema()).map(
        (item: { value: number }) => item.value,
      ),
    ).toEqual([10, 20]);
  });

  it('搜索表单只展示本地存储和对象存储', () => {
    expect(
      getStorageOptions(useGridFormSchema()).map(
        (item: { value: number }) => item.value,
      ),
    ).toEqual([10, 20]);
  });

  it('创建 S3 配置时密钥必填、编辑时允许留空保留', () => {
    const field = getField(useFormSchema(), 'config.accessSecret');
    const rules = field?.dependencies?.rules;

    expect(field?.component).toBe('VbenInputPassword');
    if (!rules) {
      throw new Error('缺少 accessSecret 动态校验规则');
    }
    expect(rules({ id: undefined, storage: 20 }).safeParse('').success).toBe(
      false,
    );
    expect(rules({ id: 1, storage: 20 }).safeParse('').success).toBe(true);
  });
});
