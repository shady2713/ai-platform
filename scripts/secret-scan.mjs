import { execFileSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const frontend = "前端代码/basic-framework-admin/apps/web-ele/src/";
const server = "后端代码/basic-framework-boot/basic-framework-server/src/";
const system =
  "后端代码/basic-framework-boot/basic-framework-module-system/src/";
// Exact public fixtures are exempt only in their owning file, never by directory.
const publicFixtures = new Map([
  [`${frontend}api/core/auth.test.ts`, new Set(["old-password"])],
  [
    `${frontend}api/request.test.ts`,
    new Set(["access-token", "previous", "new-account", "obsolete"]),
  ],
  [`${frontend}router/guard.test.ts`, new Set(["restored"])],
  [
    `${frontend}views/system/user/modules/reset-password-form.test.ts`,
    new Set(["New@123456"]),
  ],
  [
    `${frontend}store/auth.test.ts`,
    new Set([
      "valid-password",
      "password",
      "restored",
      "obsolete",
      "new-session",
    ]),
  ],
  [
    `${frontend}views/_core/authentication/login.test.ts`,
    new Set(["password"]),
  ],
  [
    `${server}main/resources/db/migration/V42__disable_shared_seed_credential.sql`,
    new Set(["!bootstrap-required"]),
  ],
  [
    `${server}main/resources/db/migration/V46__prune_unused_initialization_data.sql`,
    new Set(["!bootstrap-required"]),
  ],
  [
    `${server}test/java/com/basicframework/server/integration/RemovedCapabilityMigrationIT.java`,
    new Set(["!bootstrap-required", "deployment-specific"]),
  ],
  [
    `${system}main/java/com/basicframework/module/system/service/user/BootstrapAdminInitializer.java`,
    new Set(["!bootstrap-required"]),
  ],
  [
    `${system}test/java/com/basicframework/module/system/service/session/UserSessionServiceImplTest.java`,
    new Set(["access-token"]),
  ],
]);

export function hasSecretAssignment(path, line) {
  const assignments = line.matchAll(
    /(?:password|secret|token|api[_-]?key)["']?\s*[:=]\s*(["'])([^"'\r\n]*)(["']?)/gi,
  );
  for (const [, opening, value, closing] of assignments) {
    const isFixture =
      opening === closing && publicFixtures.get(path)?.has(value);
    if (/^[^"'{$\s]{8}/.test(value) && !isFixture) {
      return true;
    }
  }
  return false;
}

export function scanStaged() {
  const git = (...args) => execFileSync("git", args, { encoding: "utf8" });
  const files = git(
    "diff",
    "--cached",
    "--name-only",
    "-z",
    "--diff-filter=ACMR",
  )
    .split("\0")
    .filter(Boolean)
    .filter((path) => !/(\.example$|\.md$|^docs\/)/.test(path));
  return files.filter((path) =>
    git("diff", "--cached", "--unified=0", "--", path)
      .split("\n")
      .filter((line) => line.startsWith("+") && !line.startsWith("+++"))
      .some((line) => hasSecretAssignment(path, line)),
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const violations = scanStaged();
  if (violations.length > 0) {
    console.error(violations.join("\n"));
    console.error("secret-scan: possible hardcoded secret in the files above");
    process.exitCode = 1;
  }
}
