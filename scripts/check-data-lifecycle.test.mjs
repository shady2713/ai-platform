import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';

import {
  compareSets,
  finalTables,
  physicalForeignKeys,
  readMigrations,
  tablesWithDeletedColumn,
  validateSnapshotVersion,
} from './check-data-lifecycle.mjs';

const tempDirs = [];

after(() => {
  for (const dir of tempDirs) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe('快照版本契约', () => {
  const migrations = [{ name: 'V1__initial.sql' }, { name: 'V43__prune.sql' }];

  it('快照声明与实际最新版本一致时放行', () => {
    assert.deepEqual(
      validateSnapshotVersion(
        '-- Snapshot note: aligned with the authoritative Flyway migration chain through V43.',
        migrations,
      ),
      [],
    );
  });

  it('新增迁移未更新快照，或快照超前时拦截', () => {
    for (const version of [42, 44]) {
      const errors = validateSnapshotVersion(
        `-- Snapshot note: aligned with the authoritative Flyway migration chain through V${version}.`,
        migrations,
      );
      assert.equal(errors.length, 1);
      assert.match(errors[0], /最新 Flyway 迁移 V43/);
    }
  });

  it('缺失版本声明时拦截', () => {
    assert.match(
      validateSnapshotVersion('CREATE TABLE users(id bigint);', migrations)[0],
      /未声明/,
    );
  });
});

function writeFixtures(files) {
  const dir = mkdtempSync(join(tmpdir(), 'data-lifecycle-'));
  tempDirs.push(dir);
  for (const [name, sql] of Object.entries(files)) {
    writeFileSync(join(dir, name), sql);
  }
  return dir;
}

describe('readMigrations', () => {
  it('只读取 V 版本迁移并按版本号升序返回', () => {
    const dir = writeFixtures({
      'V10__later.sql': 'SELECT 10;',
      'V2__second.sql': 'SELECT 2;',
      'R__repeatable.sql': 'SELECT 0;',
      'notes.txt': 'not a migration',
      'V1__first.sql': 'SELECT 1;',
    });
    const migrations = readMigrations(dir);
    assert.deepEqual(
      migrations.map((migration) => migration.name),
      ['V1__first.sql', 'V2__second.sql', 'V10__later.sql'],
    );
    assert.equal(migrations[0].sql, 'SELECT 1;');
  });
});

describe('finalTables', () => {
  it('识别反引号、IF NOT EXISTS、换行与注释等写法变体', () => {
    const migrations = [
      {
        name: 'V1__init.sql',
        sql: [
          '-- 用户表，注释不影响解析',
          'CREATE TABLE `sys_users` (',
          '  `id` bigint NOT NULL',
          ') ENGINE = InnoDB;',
          '',
          'CREATE TABLE IF NOT EXISTS',
          '  sys_role (',
          '  `id` bigint NOT NULL',
          ') ENGINE = InnoDB;',
        ].join('\n'),
      },
    ];
    assert.deepEqual([...finalTables(migrations)].sort(), [
      'sys_role',
      'sys_users',
    ]);
  });

  it('DROP TABLE IF EXISTS 将表从最终集合移除', () => {
    const migrations = [
      {
        name: 'V1__init.sql',
        sql: 'CREATE TABLE `legacy_table` (`id` bigint NOT NULL) ENGINE = InnoDB;',
      },
      { name: 'V2__drop.sql', sql: 'DROP TABLE IF EXISTS `legacy_table`;' },
    ];
    assert.deepEqual([...finalTables(migrations)], []);
  });
});

describe('physicalForeignKeys', () => {
  it('识别 CREATE TABLE 内联约束与 ALTER TABLE 新增约束', () => {
    const migrations = [
      {
        name: 'V1__init.sql',
        sql: [
          'CREATE TABLE `sys_users` (`id` bigint NOT NULL) ENGINE = InnoDB;',
          'CREATE TABLE `sys_post` (',
          '  `id` bigint NOT NULL,',
          '  `user_id` bigint NOT NULL,',
          '  CONSTRAINT `fk_post_user` FOREIGN KEY (`user_id`) REFERENCES `sys_users` (`id`)',
          ') ENGINE = InnoDB;',
          'ALTER TABLE `sys_post`',
          '  ADD CONSTRAINT `fk_post_self` FOREIGN KEY (`id`) REFERENCES `sys_post` (`id`);',
        ].join('\n'),
      },
    ];
    assert.deepEqual([...physicalForeignKeys(migrations)].sort(), [
      'fk_post_self',
      'fk_post_user',
    ]);
  });

  it('DROP FOREIGN KEY 移除约束，所属表被 DROP 后外键随之失效', () => {
    const migrations = [
      {
        name: 'V1__init.sql',
        sql: [
          'CREATE TABLE `p` (`id` bigint NOT NULL) ENGINE = InnoDB;',
          'CREATE TABLE `c1` (',
          '  `id` bigint NOT NULL,',
          '  CONSTRAINT `fk_c1_p` FOREIGN KEY (`id`) REFERENCES `p` (`id`)',
          ') ENGINE = InnoDB;',
          'CREATE TABLE `c2` (',
          '  `id` bigint NOT NULL,',
          '  CONSTRAINT `fk_c2_p` FOREIGN KEY (`id`) REFERENCES `p` (`id`)',
          ') ENGINE = InnoDB;',
          'CREATE TABLE `c3` (',
          '  `id` bigint NOT NULL,',
          '  CONSTRAINT `fk_c3_p` FOREIGN KEY (`id`) REFERENCES `p` (`id`)',
          ') ENGINE = InnoDB;',
        ].join('\n'),
      },
      {
        name: 'V2__evolve.sql',
        sql: [
          'ALTER TABLE `c1` DROP FOREIGN KEY `fk_c1_p`;',
          'DROP TABLE IF EXISTS `c2`;',
        ].join('\n'),
      },
    ];
    // fk_c1_p 被显式 DROP，fk_c2_p 所属表已删除，仅 fk_c3_p 存活
    assert.deepEqual([...physicalForeignKeys(migrations)], ['fk_c3_p']);
  });
});

describe('tablesWithDeletedColumn', () => {
  it('CREATE TABLE 含 deleted 列时登记，不含时不登记', () => {
    const migrations = [
      {
        name: 'V1__init.sql',
        sql: [
          'CREATE TABLE `with_deleted` (',
          '  `id` bigint NOT NULL,',
          "  `deleted` bit(1) NOT NULL DEFAULT b'0'",
          ') ENGINE = InnoDB;',
          'CREATE TABLE `without_flag` (',
          '  `id` bigint NOT NULL',
          ') ENGINE = InnoDB;',
        ].join('\n'),
      },
    ];
    assert.deepEqual(
      [...tablesWithDeletedColumn(migrations)],
      ['with_deleted'],
    );
  });

  it('ALTER TABLE ADD/DROP deleted 列切换登记状态', () => {
    const migrations = [
      {
        name: 'V1__init.sql',
        sql: 'CREATE TABLE `t1` (`id` bigint NOT NULL) ENGINE = InnoDB;',
      },
      {
        name: 'V2__add.sql',
        sql: "ALTER TABLE `t1` ADD COLUMN `deleted` bit(1) NOT NULL DEFAULT b'0';",
      },
      { name: 'V3__drop.sql', sql: 'ALTER TABLE `t1` DROP COLUMN `deleted`;' },
    ];
    // 截至 V2 处于登记状态，V3 删除后移出集合
    assert.deepEqual(
      [...tablesWithDeletedColumn(migrations.slice(0, 2))],
      ['t1'],
    );
    assert.deepEqual([...tablesWithDeletedColumn(migrations)], []);
  });
});

describe('compareSets', () => {
  it('集合一致时不产生错误（放行）', () => {
    const errors = [];
    compareSets(new Set(['a', 'b']), new Set(['b', 'a']), '最终表', errors);
    assert.deepEqual(errors, []);
  });

  it('双向漂移分别报 未登记 与 登记项不存在（拦截）', () => {
    const errors = [];
    compareSets(
      new Set(['a', 'extra']),
      new Set(['a', 'ghost']),
      '最终表',
      errors,
    );
    assert.deepEqual(errors, [
      '最终表 未登记：extra',
      '最终表 登记项不存在：ghost',
    ]);
  });
});

describe('迁移与快照双向比对', () => {
  const createUsersSql = [
    'CREATE TABLE `sys_users` (',
    '  `id` bigint NOT NULL,',
    "  `deleted` bit(1) NOT NULL DEFAULT b'0',",
    '  CONSTRAINT `fk_users_self` FOREIGN KEY (`id`) REFERENCES `sys_users` (`id`)',
    ') ENGINE = InnoDB;',
  ].join('\n');

  it('迁移与快照一致时放行，快照漂移时拦截', () => {
    const dir = writeFixtures({
      'V1__init.sql': createUsersSql,
      'basic_framework.sql': createUsersSql,
    });
    const migrations = readMigrations(dir);
    const snapshot = [
      {
        name: 'basic_framework.sql',
        sql: readFileSync(join(dir, 'basic_framework.sql'), 'utf8'),
      },
    ];

    const errors = [];
    compareSets(
      finalTables(snapshot),
      finalTables(migrations),
      '快照表',
      errors,
    );
    compareSets(
      physicalForeignKeys(snapshot),
      physicalForeignKeys(migrations),
      '快照物理外键',
      errors,
    );
    compareSets(
      tablesWithDeletedColumn(snapshot),
      tablesWithDeletedColumn(migrations),
      '快照逻辑删除列',
      errors,
    );
    assert.deepEqual(errors, []);

    const drifted = [
      {
        name: 'basic_framework.sql',
        sql: `${createUsersSql}\nCREATE TABLE \`sys_ghost\` (\`id\` bigint NOT NULL) ENGINE = InnoDB;`,
      },
    ];
    const driftErrors = [];
    compareSets(
      finalTables(drifted),
      finalTables(migrations),
      '快照表',
      driftErrors,
    );
    assert.deepEqual(driftErrors, ['快照表 未登记：sys_ghost']);
  });
});
