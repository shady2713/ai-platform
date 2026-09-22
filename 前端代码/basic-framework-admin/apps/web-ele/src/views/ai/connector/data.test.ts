import { describe, expect, it } from 'vitest';

import {
  AI_CONNECTOR_PERMISSIONS,
  buildConfigJson,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
} from './data';

describe('ai connector data', () => {
  it('权限码与 V66/V67 迁移种子一致', () => {
    expect(AI_CONNECTOR_PERMISSIONS).toEqual({
      create: 'ai:connector:create',
      delete: 'ai:connector:delete',
      import: 'ai:connector:import',
      operation: 'ai:connector:operation',
      probe: 'ai:connector:probe',
      query: 'ai:connector:query',
      update: 'ai:connector:update',
    });
  });

  it('表格列与搜索表单覆盖类型/状态过滤', () => {
    const columns = useGridColumns() ?? [];
    expect(columns.map((column) => column.field)).toContain('connectorType');
    expect(columns.map((column) => column.field)).toContain('status');
    const typeColumn = columns.find(
      (column) => column.field === 'connectorType',
    );
    expect(
      (typeColumn?.formatter as (params: { cellValue: string }) => string)({
        cellValue: 'MYSQL',
      }),
    ).toBe('MySQL 只读库');
    const credentialColumn = columns.find(
      (column) => column.field === 'credentialConfigured',
    );
    expect(
      (
        credentialColumn?.formatter as (params: {
          cellValue: boolean;
        }) => string
      )({
        cellValue: true,
      }),
    ).toBe('已配置');
    expect(useGridFormSchema().map((schema) => schema.fieldName)).toEqual([
      'code',
      'connectorType',
      'status',
    ]);
  });

  it('表单只有声明式字段：没有连接串/SQL/脚本输入面', () => {
    const fields = useFormSchema().map((schema) => schema.fieldName);
    expect(fields).toContain('host');
    expect(fields).toContain('allowedObjects');
    expect(fields).toContain('baseUrl');
    expect(
      fields.some((field) =>
        /(?:^|[^a-z])(?:sql|script|jdbc|connectionstring)(?:[^a-z]|$)/i.test(
          String(field),
        ),
      ),
    ).toBe(false);
  });

  it('mySQL 配置只拼结构化字段并保留授权对象', () => {
    const config = JSON.parse(
      buildConfigJson({
        allowedObjects: 'crm.orders\n\ncrm.order_view',
        configKind: 'MYSQL',
        database: 'crm',
        host: 'db.internal',
        port: 3306,
        sslMode: 'REQUIRED',
        username: 'readonly',
      }),
    );
    expect(config).toEqual({
      allowedObjects: ['crm.orders', 'crm.order_view'],
      database: 'crm',
      host: 'db.internal',
      port: 3306,
      sslMode: 'REQUIRED',
      username: 'readonly',
    });
    expect(JSON.stringify(config)).not.toContain('jdbc:');
  });

  it('hTTP 配置按是否填写秘密决定认证方式', () => {
    expect(
      JSON.parse(
        buildConfigJson({
          baseUrl: 'https://crm.example.com',
          configKind: 'HTTP',
          credential: 'secret',
          method: 'GET',
        }),
      ),
    ).toEqual({
      authType: 'BEARER',
      baseUrl: 'https://crm.example.com',
      method: 'GET',
    });
    expect(
      JSON.parse(
        buildConfigJson({
          baseUrl: 'https://crm.example.com',
          configKind: 'HTTP',
          credential: '',
        }),
      ).authType,
    ).toBe('NONE');
  });
});
