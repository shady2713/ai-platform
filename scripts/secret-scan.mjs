import { execFileSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const frontend = "前端代码/basic-framework-admin/apps/web-ele/src/";
const server = "后端代码/basic-framework-boot/basic-framework-server/src/";
const system =
  "后端代码/basic-framework-boot/basic-framework-module-system/src/";
const moduleAi =
  "后端代码/basic-framework-boot/basic-framework-module-ai/src/";
// Reviewed test values and UI literals are exempt only in their owning file.
// No file or directory is exempt: changed values and other assignments still fail.
const publicFixtures = new Map([
  [
    `${frontend}api/core/auth.test.ts`,
    new Set(["old-password", "captcha-token", "new-pass"]),
  ],
  [`${frontend}api/infra/file/index.test.ts`, new Set(["upload-token"])],
  [`${frontend}api/system/user/index.test.ts`, new Set(["new-password"])],
  [
    `${frontend}api/system/user/profile/index.test.ts`,
    new Set(["new-pass", "old-pass"]),
  ],
  [
    `${frontend}components/upload/use-upload.test.ts`,
    new Set(["upload-token"]),
  ],
  [
    `${frontend}api/request.test.ts`,
    new Set([
      "access-token",
      "previous",
      "new-account",
      "obsolete",
      "new-access-token",
      "expired-token",
    ]),
  ],
  [`${frontend}router/guard.test.ts`, new Set(["restored", "access-token"])],
  [
    `${frontend}views/_core/authentication/forget-password.test.ts`,
    new Set(["StrongPassword1"]),
  ],
  [
    `${frontend}views/_core/profile/modules/reset-pwd.test.ts`,
    new Set(["NewPassword1", "OldPassword1"]),
  ],
  [
    "前端代码/basic-framework-admin/internal/vite-config/src/plugins/extra-app-config.test.ts",
    new Set(["must-not-reach-the-browser"]),
  ],
  [
    "前端代码/basic-framework-admin/packages/@core/ui-kit/shadcn-ui/src/components/input-password/input-password.vue",
    new Set(["modelValue"]),
  ],
  [
    "前端代码/basic-framework-admin/packages/effects/common-ui/src/components/captcha/verification/contract.test.ts",
    new Set(["one-time-token"]),
  ],
  [
    "前端代码/basic-framework-admin/packages/effects/layouts/src/widgets/lock-screen/lock-screen-modal.test.ts",
    new Set(["candidate-password"]),
  ],
  [
    "前端代码/basic-framework-admin/packages/effects/layouts/src/widgets/lock-screen/lock-screen.test.ts",
    new Set(["candidate-password"]),
  ],
  [
    "前端代码/basic-framework-admin/packages/locales/src/langs/en-US/authentication.json",
    new Set(["Password"]),
  ],
  [
    "前端代码/basic-framework-admin/packages/locales/src/langs/en-US/ui.json",
    new Set(["Password"]),
  ],
  [
    // A01/A04 单测里的一次性秘密与票据明文：只在本文件内作为被测值，不指向任何真实凭据。
    `${moduleAi}test/java/com/basicframework/module/ai/service/application/AiApplicationServiceImplTest.java`,
    new Set(["aiapp_secret"]),
  ],
  [
    `${moduleAi}test/java/com/basicframework/module/ai/service/auth/AiTicketServiceImplTest.java`,
    new Set(["aiapp_secret", "some-token"]),
  ],
  [
    `${system}test/java/com/basicframework/module/system/controller/admin/auth/AuthControllerTest.java`,
    new Set(["access-token", "refresh-token"]),
  ],
  [
    `${system}test/java/com/basicframework/module/system/controller/admin/user/UserProfileControllerTest.java`,
    new Set(["CurrentPassword1"]),
  ],
  [
    `${system}test/java/com/basicframework/module/system/service/sms/SmsTemplateServiceImplTest.java`,
    new Set(["provider-secret-token"]),
  ],
  [
    `${server}test/java/com/basicframework/server/integration/AbstractPersistenceIntegrationTest.java`,
    new Set(["integration-only"]),
  ],
  [
    // A09 页面与用例里已复核的字面量：事件绑定名与一次性秘密的测试替身。
    `${frontend}views/ai/application/index.vue`,
    new Set(["handleSecret"]),
  ],
  [
    `${frontend}views/ai/application/index.test.ts`,
    new Set(["aiapp_once"]),
  ],
  [
    `${frontend}views/ai/application/modules/modules.test.ts`,
    new Set(["aiapp_once", "aiapp_rotated"]),
  ],
  [
    // A05 身份隔离 IT 自建管理端用户的集成环境口令（仅测试值，不指向任何真实凭据）。
    `${server}test/java/com/basicframework/server/integration/AiIdentityIsolationIT.java`,
    new Set(["identity-integration-password"]),
  ],
  [
    `${server}test/java/com/basicframework/server/integration/PackagedJarBootSmokeIT.java`,
    new Set([
      "integration-only-db",
      "integration-only-flyway",
      "integration-only-root",
      "integration-only-redis",
    ]),
  ],
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
  // O08 开放平台：目录示例里的占位符与调试用例里的假凭据字面量（不是真实凭据）
  [
    `${frontend}api/ai/open-platform/index.ts`,
    new Set(["<APP_SECRET>", "<TICKET>"]),
  ],
  [
    `${frontend}api/ai/open-platform/index.test.ts`,
    new Set(["aitkt_once", "aiapp_secret"]),
  ],
  [
    `${frontend}views/ai/open-platform/data.test.ts`,
    new Set(["<APP_SECRET>", "aitkt_abcdefghijkl", "aiapp_realsecret"]),
  ],
  [
    `${frontend}views/ai/open-platform/index.test.ts`,
    new Set(["aitkt_once", "aiapp_secret"]),
  ],
  // K01：向量索引集成测试里的容器本地 API Key（仅测试容器内使用）
  [
    `${server}test/java/com/basicframework/server/integration/AiKnowledgeIndexQdrantIT.java`,
    new Set(["k01-it-api-key", "wrong-key"]),
  ],
  // D02：请求头构造单测里的解密替身明文（只在本文件内作为被测值，不指向任何真实凭据）
  [
    `${moduleAi}test/java/com/basicframework/module/ai/adapter/connector/http/AiConnectorAuthHeadersTest.java`,
    new Set(["it-connector-secret"]),
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
