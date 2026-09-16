import assert from 'node:assert/strict';
import test from 'node:test';

import {
  controllerValidationFailures,
  missingClassValidated,
  unguardedAnnotatedIdParams,
  unguardedBareIdParams,
  unguardedReqVoParams,
} from './check-controller-validation.mjs';

test('拒绝无 @Positive 的 @RequestParam Long id', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> deleteDemo(@RequestParam("id") Long id) { return null; }
      public CommonResult<Boolean> getDemo(@PathVariable("demoId") Long demoId) { return null; }
    }
  `;

  const failures = unguardedAnnotatedIdParams(source);
  assert.equal(failures.length, 2);
  assert.equal(failures[0].rule, 'id-positive');
  assert.equal(failures[0].line, 3);
  assert.equal(failures[1].line, 4);
});

test('允许带 @Positive 的 id 参数与非 id 的 Long 参数', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> deleteDemo(@RequestParam("id") @Positive Long id) { return null; }
      public CommonResult<Boolean> batch(@RequestParam("ids") List<Long> ids) { return null; }
      public CommonResult<Boolean> bounded(@RequestParam("ids") @Size(min = 1) List<@Positive Long> ids) {
        return null;
      }
      public CommonResult<Boolean> presign(@RequestParam("size") @Positive(message = "x") Long size) {
        return null;
      }
      public CommonResult<Boolean> count(@RequestParam("count") Integer count) { return null; }
    }
  `;

  assert.deepEqual(unguardedAnnotatedIdParams(source), []);
});

test('拒绝完全无注解的 Long id 参数，忽略注释与字符串中的示例', () => {
  const source = `
    class DemoController {
      // public CommonResult<Boolean> legacy(@RequestParam("id") Long id) { return null; }
      String example = "@RequestParam(\\"id\\") Long id";
      public CommonResult<Boolean> listRoleMenus(Long roleId) { return null; }
      public CommonResult<Boolean> call() { return service.deleteDemo(id); }
    }
  `;

  const failures = unguardedBareIdParams(source);
  assert.equal(failures.length, 1);
  assert.equal(failures[0].line, 5);
  assert.deepEqual(unguardedAnnotatedIdParams(source), []);
});

test('拒绝无 @Valid/@Validated 的 ReqVO 入参，兼容多行注解与第二个参数位', () => {
  const source = `
    class DemoController {
      public void export(ConfigPageReqVO exportReqVO, HttpServletResponse response) { }
      public void exportTwo(
              HttpServletResponse response,
              @Valid ConfigPageReqVO exportReqVO) { }
      public CommonResult<Long> create(@RequestBody ConfigSaveReqVO createReqVO) { return null; }
      public CommonResult<Long> update(@Valid @RequestBody ConfigSaveReqVO updateReqVO) { return null; }
      public CommonResult<PageResult<ConfigRespVO>> page(@Validated ConfigPageReqVO pageReqVO) { return null; }
    }
  `;

  const failures = unguardedReqVoParams(source);
  assert.equal(failures.length, 2);
  assert.equal(failures[0].line, 3);
  assert.equal(failures[1].line, 7);
});

test('声明 @Positive 的类必须带类级 @Validated', () => {
  const missing = `
    @RestController
    class DemoController {
      public CommonResult<Boolean> get(@RequestParam("id") @Positive Long id) { return null; }
    }
  `;
  const present = `
    @RestController
    @Validated
    class DemoController {
      public CommonResult<Boolean> get(@RequestParam("id") @Positive Long id) { return null; }
    }
  `;

  assert.equal(missingClassValidated(missing).length, 1);
  assert.deepEqual(missingClassValidated(present), []);
});

test('final 修饰的参数不得绕过 @Positive 校验', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> deleteDemo(@RequestParam("id") final Long id) { return null; }
      public CommonResult<Boolean> getDemo(@RequestParam("id") @Positive final Long id) { return null; }
    }
  `;

  const failures = unguardedAnnotatedIdParams(source);
  assert.equal(failures.length, 1);
  assert.equal(failures[0].rule, 'id-positive');
  assert.equal(failures[0].line, 3);
});

test('snake_case 的 id 参数同样受 @Positive 约束', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> list(@RequestParam("dept_id") Long dept_id) { return null; }
    }
  `;

  const failures = unguardedAnnotatedIdParams(source);
  assert.equal(failures.length, 1);
  assert.equal(failures[0].rule, 'id-positive');
});

test('无法解析的参数声明按 fail-closed 报错而非静默放行', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> stats(@RequestParam("broken") counts) { return null; }
    }
  `;

  const failures = unguardedAnnotatedIdParams(source);
  assert.equal(failures.length, 1);
  assert.equal(failures[0].rule, 'declaration-parse');
  assert.equal(failures[0].line, 3);
});

test('多泛型参数在泛型逗号处不被截断，合法解析不误报', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> stats(@RequestParam Map<String, Long> counts) { return null; }
    }
  `;

  assert.deepEqual(unguardedAnnotatedIdParams(source), []);
});

test('约束注解写在绑定注解之前同样生效', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> get(@Positive @RequestParam("id") Long id) { return null; }
    }
  `;

  assert.deepEqual(unguardedAnnotatedIdParams(source), []);
});

test('原始 long 与全限定绑定注解同样受 @Positive 约束', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> get(@RequestParam("id") long id) { return null; }
      public CommonResult<Boolean> find(@org.springframework.web.bind.annotation.PathVariable("userId") Long userId) { return null; }
    }
  `;

  const failures = unguardedAnnotatedIdParams(source);
  assert.equal(failures.length, 2);
  assert.equal(failures[0].rule, 'id-positive');
  assert.equal(failures[1].rule, 'id-positive');
});

test('字符串与注释中的 @Positive 不触发类级 @Validated 要求', () => {
  const source = `
    @RestController
    class DemoController {
      String doc = "@Positive";
      // 注释示例：@Positive
      public CommonResult<Boolean> get(@RequestParam("kw") String keyword) { return null; }
    }
  `;

  assert.deepEqual(missingClassValidated(source), []);
});

test('无注解的 final Long id 参数同样被拒绝', () => {
  const source = `
    class DemoController {
      public CommonResult<Boolean> listRoleMenus(final Long roleId) { return null; }
    }
  `;

  const failures = unguardedBareIdParams(source);
  assert.equal(failures.length, 1);
  assert.equal(failures[0].rule, 'id-positive');
});

test('聚合入口汇总各规则并忽略字符串字面量', () => {
  const source = `
    class DemoController {
      String doc = "ConfigPageReqVO exportReqVO,";
      public void export(ConfigPageReqVO exportReqVO, HttpServletResponse response) { }
      public CommonResult<Boolean> get(@RequestParam("id") Long id) { return null; }
      public CommonResult<Boolean> remove(@RequestParam("id") @Positive Long id) { return null; }
    }
  `;

  const failures = controllerValidationFailures(source);
  assert.equal(failures.length, 3);
  assert.deepEqual(
    failures.map((failure) => failure.rule),
    ['id-positive', 'vo-valid', 'class-validated'],
  );
});
