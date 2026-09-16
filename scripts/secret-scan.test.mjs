import assert from "node:assert/strict";
import test from "node:test";
import { hasSecretAssignment } from "./secret-scan.mjs";

const frontend = "前端代码/basic-framework-admin/apps/web-ele/src/";
const migration =
  "后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/";
const assignment = (value) => `password = '${value}'`;

test("initial import accepts exact reviewed literals without trusting their files", () => {
  const cases = [
    [`${frontend}api/core/auth.test.ts`, "captcha-token"],
    [`${frontend}api/infra/file/index.test.ts`, "upload-token"],
    [
      `${frontend}views/_core/profile/modules/reset-pwd.test.ts`,
      "NewPassword1",
    ],
    [
      "前端代码/basic-framework-admin/packages/locales/src/langs/en-US/ui.json",
      "Password",
    ],
    [
      "前端代码/basic-framework-admin/packages/@core/ui-kit/shadcn-ui/src/components/input-password/input-password.vue",
      "modelValue",
    ],
    [
      "后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server/integration/PackagedJarBootSmokeIT.java",
      "integration-only-db",
    ],
  ];
  for (const [path, value] of cases) {
    assert.equal(hasSecretAssignment(path, assignment(value)), false, path);
    assert.equal(
      hasSecretAssignment("application.yml", assignment(value)),
      true,
      path,
    );
    assert.equal(
      hasSecretAssignment(path, assignment(value + "-changed")),
      true,
      path,
    );
    assert.equal(
      hasSecretAssignment(path, assignment(value).slice(0, -1)),
      true,
      path,
    );
    assert.equal(
      hasSecretAssignment(
        path,
        `${assignment(value)}; ${assignment("synthetic-" + "credential")}`,
      ),
      true,
      path,
    );
  }
});

test("public fixtures are exempt only in their owning file", () => {
  assert.equal(
    hasSecretAssignment(
      `${frontend}store/auth.test.ts`,
      assignment("password"),
    ),
    false,
  );
  assert.equal(
    hasSecretAssignment(`${frontend}store/auth.ts`, assignment("password")),
    true,
  );
  assert.equal(
    hasSecretAssignment("unrelated.test.ts", assignment("password")),
    true,
  );
});

test("only the complete bootstrap sentinel is exempt in known migrations", () => {
  for (const name of [
    "V42__disable_shared_seed_credential.sql",
    "V46__prune_unused_initialization_data.sql",
  ]) {
    assert.equal(
      hasSecretAssignment(migration + name, assignment("!bootstrap-required")),
      false,
    );
    assert.equal(
      hasSecretAssignment(
        migration + name,
        assignment("!bootstrap-required-extra"),
      ),
      true,
    );
  }
  assert.equal(
    hasSecretAssignment("application.yml", assignment("!bootstrap-required")),
    true,
  );
});

test("fixtures cannot hide another credential on the same line or in the same file", () => {
  const path = `${frontend}store/auth.test.ts`;
  const generated = "synthetic-" + "credential";
  assert.equal(hasSecretAssignment(path, assignment(generated)), true);
  assert.equal(
    hasSecretAssignment(
      path,
      `${assignment("password")}; ${assignment(generated)}`,
    ),
    true,
  );
});

test("environment placeholders remain valid and credential keys remain case-insensitive", () => {
  assert.equal(
    hasSecretAssignment("application.yml", assignment("${DEPLOY_PASSWORD}")),
    false,
  );
  const generated = "synthetic-" + "credential";
  for (const key of [
    "password",
    "SECRET",
    "accessToken",
    "api_key",
    "api-key",
  ]) {
    assert.equal(
      hasSecretAssignment("application.yml", `${key}: "${generated}"`),
      true,
    );
  }
});

test("an incomplete assignment cannot become a fixture exception", () => {
  assert.equal(
    hasSecretAssignment(
      `${frontend}store/auth.test.ts`,
      assignment("password").slice(0, -1),
    ),
    true,
  );
});
