import { describe, expect, it } from 'vitest';

import {
  AI_MODEL_ENDPOINT_PERMISSIONS,
  CAPABILITY_OPTIONS,
  PROBE_KIND_LABELS,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
  useRotateSchema,
} from './data';

/**
 * M06 模型管理页面契约：
 * 权限码与 V48/V49 迁移种子一致；表单校验与后端长度上限一致；凭据只提交不回填。
 */
describe('ai model endpoint page data', () => {
  const formSchema = useFormSchema();
  const fieldOf = (name: string) =>
    formSchema.find((item) => item.fieldName === name);
  const propsOf = (name: string) =>
    (fieldOf(name)?.componentProps ?? {}) as Record<string, unknown>;

  it('declares permission codes matching migration seeds', () => {
    expect(AI_MODEL_ENDPOINT_PERMISSIONS).toEqual({
      create: 'ai:model-endpoint:create',
      delete: 'ai:model-endpoint:delete',
      probe: 'ai:model-endpoint:probe',
      query: 'ai:model-endpoint:query',
      update: 'ai:model-endpoint:update',
    });
  });

  it('offers the full capability vocabulary from the backend enum', () => {
    expect(CAPABILITY_OPTIONS.map((item) => item.value)).toEqual([
      'TEXT',
      'TEXT_STREAM',
      'STRUCTURED_OUTPUT',
      'TOOL_CALLING',
      'EMBEDDING',
    ]);
    expect(Object.keys(PROBE_KIND_LABELS)).toEqual([
      'CONNECTIVITY',
      'TEXT',
      'TEXT_STREAM',
      'STRUCTURED_OUTPUT',
      'TOOL_CALLING',
      'EMBEDDING',
    ]);
  });

  it('keeps required fields and length limits aligned with the backend', () => {
    expect(fieldOf('name')?.rules).toBeDefined();
    expect(fieldOf('provider')?.rules).toBe('required');
    expect(fieldOf('capabilities')?.rules).toBe('required');
    expect(propsOf('baseUrl').maxlength).toBe(512);
    expect(propsOf('modelId').maxlength).toBe(128);
    expect(propsOf('name').maxlength).toBe(128);
  });

  it('never prefills the stored credential on edit', () => {
    // 表单只有一个密码输入框，且提示留空表示保留；schema 里不存在任何回填字段
    const credentialFields = formSchema.filter(
      (item) => item.fieldName === 'credential',
    );
    expect(credentialFields).toHaveLength(1);
    expect(credentialFields[0]?.component).toBe('InputPassword');
    expect(String(propsOf('credential').placeholder)).toContain('留空表示保留');
    // 列表列只展示"是否已配置"，不渲染凭据本身
    const columns = useGridColumns() ?? [];
    expect(columns.map((column) => column.field)).toContain(
      'credentialConfigured',
    );
    expect(columns.map((column) => column.field)).not.toContain('credential');
  });

  it('requires a new credential when rotating', () => {
    const rotateSchema = useRotateSchema();
    expect(rotateSchema).toHaveLength(1);
    expect(rotateSchema[0]?.component).toBe('InputPassword');
    expect(rotateSchema[0]?.rules).toBeDefined();
  });

  it('exposes search fields for name and provider only', () => {
    expect(useGridFormSchema().map((item) => item.fieldName)).toEqual([
      'name',
      'provider',
    ]);
  });
});
