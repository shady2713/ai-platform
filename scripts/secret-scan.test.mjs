import assert from "node:assert/strict";
import test from "node:test";
import { hasSecretAssignment } from "./secret-scan.mjs";

const frontend = "前端代码/basic-framework-admin/apps/web-ele/src/";
const migration =
  "后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/";
const assignment = (value) => `password = '${value}'`;

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
