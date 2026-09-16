import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  runtimeRegistrations,
  snapshotTableColumns,
  validateDataPermissionContract,
} from './check-data-permission.mjs';

const completeCatalog = {
  version: 3,
  exemptions: [
    {
      id: 'global-table',
      reason: '全局配置表由功能权限保护，不具有行级部门归属。',
      tables: ['global_config'],
      controls: [
        {
          kind: 'function-permission',
          tables: ['global_config'],
          evidence: [
            {
              table: 'global_config',
              path:
                '后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java',
              contains: "@PreAuthorize('global:config:query')",
            },
          ],
        },
      ],
    },
  ],
};

const completeEvidenceSources = new Map([
  [
    '后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java',
    "class GlobalConfigController { @PreAuthorize('global:config:query') void page() {} }",
  ],
]);

describe('snapshotTableColumns', () => {
  it('读取最终快照中的表和列', () => {
    const columns = snapshotTableColumns(
      'CREATE TABLE `orders` (`id` bigint NOT NULL, `dept_id` bigint NOT NULL) ENGINE = InnoDB;',
    );
    assert.deepEqual([...columns.get('orders')], ['id', 'dept_id']);
  });
});

describe('runtimeRegistrations', () => {
  it('读取显式部门与用户列登记', () => {
    const result = runtimeRegistrations([
      {
        path: 'DataPermissionConfiguration.java',
        source: [
          'rule.addDeptColumn("orders", "dept_id");',
          'rule.addUserColumn("orders", "owner_id");',
        ].join('\n'),
      },
    ]);
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.registrations.get('orders'), {
      deptColumn: 'dept_id',
      userColumn: 'owner_id',
    });
  });

  it('拒绝无法机械核对的 Class 重载登记', () => {
    const result = runtimeRegistrations([
      {
        path: 'DataPermissionConfiguration.java',
        source: 'rule.addDeptColumn(OrderDO.class);',
      },
    ]);
    assert.equal(result.registrations.size, 0);
    assert.match(result.errors[0], /必须使用显式表名和列名/);
  });
});

describe('validateDataPermissionContract', () => {
  function validate(overrides = {}) {
    return validateDataPermissionContract({
      catalog: completeCatalog,
      platformTables: new Set(['QRTZ_LOCKS']),
      registrations: new Map([
        ['orders', { deptColumn: 'dept_id', userColumn: 'owner_id' }],
      ]),
      schemaTables: new Set(['orders', 'global_config', 'QRTZ_LOCKS']),
      snapshotColumns: new Map([
        ['orders', new Set(['id', 'dept_id', 'owner_id'])],
        ['global_config', new Set(['id'])],
        ['QRTZ_LOCKS', new Set(['LOCK_NAME'])],
      ]),
      evidenceSources: completeEvidenceSources,
      ...overrides,
    }).errors;
  }

  it('保护、豁免与平台托管三类覆盖全部表时放行', () => {
    assert.deepEqual(validate(), []);
  });

  it('新增应用表未分类时默认阻断', () => {
    const errors = validate({
      schemaTables: new Set([
        'orders',
        'global_config',
        'new_business_table',
        'QRTZ_LOCKS',
      ]),
      snapshotColumns: new Map([
        ['orders', new Set(['id', 'dept_id', 'owner_id'])],
        ['global_config', new Set(['id'])],
        ['new_business_table', new Set(['id'])],
        ['QRTZ_LOCKS', new Set(['LOCK_NAME'])],
      ]),
    });
    assert.deepEqual(errors, [
      '应用表未登记数据权限或豁免：new_business_table',
    ]);
  });

  it('运行时列不存在或同一张表同时保护和豁免时阻断', () => {
    const errors = validate({
      catalog: {
        version: 3,
        exemptions: [
          {
            id: 'conflict',
            reason: '该错误夹具用于验证保护与豁免冲突能够被门禁阻断。',
            tables: ['orders', 'global_config'],
            controls: [
              {
                kind: 'function-permission',
                tables: ['orders', 'global_config'],
                evidence: [
                  {
                    table: 'global_config',
                    path:
                      '后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java',
                    contains: "@PreAuthorize('global:config:query')",
                  },
                  {
                    table: 'orders',
                    path:
                      '后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java',
                    contains: "@PreAuthorize('global:config:query')",
                  },
                ],
              },
            ],
          },
        ],
      },
      registrations: new Map([['orders', { deptColumn: 'missing_dept' }]]),
    });
    assert.deepEqual(errors, [
      '数据权限表同时被保护和豁免：orders',
      '数据权限运行时列不存在：orders.missing_dept (deptColumn)',
    ]);
  });

  it('豁免未声明控制机制或未覆盖全部表时阻断', () => {
    const errors = validate({
      catalog: {
        version: 3,
        exemptions: [
          {
            id: 'incomplete',
            reason: '该夹具验证每张豁免表都必须关联可机械核对的控制机制。',
            tables: ['global_config', 'orphan_table'],
            controls: [],
          },
        ],
      },
      schemaTables: new Set(['orders', 'global_config', 'orphan_table', 'QRTZ_LOCKS']),
      snapshotColumns: new Map([
        ['orders', new Set(['id', 'dept_id', 'owner_id'])],
        ['global_config', new Set(['id'])],
        ['orphan_table', new Set(['id'])],
        ['QRTZ_LOCKS', new Set(['LOCK_NAME'])],
      ]),
    });

    assert.deepEqual(errors, [
      '数据权限豁免 controls 必须是非空数组：incomplete',
      '数据权限豁免表缺少控制机制：global_config (incomplete)',
      '数据权限豁免表缺少控制机制：orphan_table (incomplete)',
    ]);
  });

  it('豁免证据文件不存在或源码片段漂移时阻断', () => {
    const errors = validate({
      evidenceSources: new Map(),
    });

    assert.deepEqual(errors, [
      '数据权限豁免证据文件不存在：后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java (global-table)',
    ]);

    const staleErrors = validate({
      evidenceSources: new Map([
        [
          '后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java',
          'class GlobalConfigController {}',
        ],
      ]),
    });
    assert.deepEqual(staleErrors, [
      '数据权限豁免证据已漂移：后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java 缺少 @PreAuthorize(\'global:config:query\') (global-table)',
    ]);
  });

  it('控制组内每张表都必须拥有显式绑定的生产证据', () => {
    const errors = validate({
      catalog: {
        version: 3,
        exemptions: [
          {
            id: 'partially-evidenced',
            reason: '该夹具验证共享控制组不能用一张表的证据替其他表兜底。',
            tables: ['global_config', 'orders'],
            controls: [
              {
                kind: 'function-permission',
                tables: ['global_config', 'orders'],
                evidence: [
                  {
                    table: 'global_config',
                    path:
                      '后端代码/basic-framework-boot/example/src/main/java/GlobalConfigController.java',
                    contains: "@PreAuthorize('global:config:query')",
                  },
                ],
              },
            ],
          },
        ],
      },
    });

    assert.deepEqual(errors, [
      '数据权限豁免表缺少逐表证据：orders (partially-evidenced)',
      '数据权限表同时被保护和豁免：orders',
    ]);
  });
});
