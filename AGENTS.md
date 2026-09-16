# AGENTS.md — basic-framework engineering standard

This file is the single source of truth for engineering rules in this
repository, adapted from the deepseek-harness engineering model
(https://github.com/deepseek-ai/deepseek-harness) to this stack:
Spring Boot 3 / Java 17 backend (`后端代码/basic-framework-boot`),
Vue 3 + TypeScript monorepo frontend (`前端代码/basic-framework-admin`),
MySQL 8 schema (`数据库文件/`).

Requirements described as CI-enforced must have a blocking gate and a test
that demonstrates rejection of a violation. Review guidance is identified
separately; a green build does not prove that guidance has been reviewed.

## Repository layout

```
后端代码/basic-framework-boot/     Maven multi-module backend
  basic-framework-dependencies/    unified dependency versions (BOM)
  basic-framework-core/            starters = capability seams
  basic-framework-module-system-api/  system 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-system/   system management module
  basic-framework-module-infra-api/   infra 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-infra/    infrastructure module
  basic-framework-server/          boot assembly (the only app entry)
前端代码/basic-framework-admin/    pnpm + turbo monorepo (apps/web-ele)
数据库文件/                        schema + seed data (sanitized)
docs/                              architecture and decision records
ops/prometheus/                    alerting rules for security signals (T1/T2/T3)
```

## Architecture rules

- **A starter is a capability seam.** It declares a contract
  (auto-configuration + properties), provides the implementation, and is
  consumed through that contract only. Business modules never reach into
  another module's internals; cross-module calls go through the api
  package of the owning module.
- **Extension points, not core patches.** New cross-cutting behavior
  (auth, logging, rate limiting, data permission) attaches to a
  documented interceptor/filter/annotation in `basic-framework-core`.
  Changing an existing core behavior requires updating the owning
  starter's README in the same change.
- **Explicit > implicit at module boundaries.** Defaults live in the
  owning auto-configuration's `Properties` class, never as scattered
  `?? default` / hardcoded literals in business code.
- **No hardcoded tunables or secrets in code.** Anything that varies by
  deployment is a validated configuration property. Secrets come from
  environment variables; no password, token, or internal IP is committed.
- Production dependencies use constructor injection. Legacy `@Resource`,
  `@Autowired`, `@Inject`, and field-level `@Value` occurrences are governed by
  `docs/contracts/field-injection-baseline.json`; neither a file nor the total
  may increase, and new files must contain none.
- **Misconfiguration fails loud at startup**, not at first request.
  `ProductionConfigurationEnvironmentPostProcessor` is the pattern;
  extend the same fail-closed validation to every profile.
- **Registrations clean up after themselves.** Anything registered at
  runtime (jobs, listeners, caches) has a defined teardown path.

## Security baseline (fail-closed defaults)

- No account can be created without an explicit admin action: public
  registration endpoints stay deleted/disabled in seed data.
- Known initial passwords are enforced at runtime, not by documentation:
  `system_users.must_change_password` blocks session issuance at login
  until the password is rotated (`POST /system/auth/change-expired-password`);
  the seed admin and admin-created/reset accounts carry the flag.
- Seed data ships sanitized: no real login history, no demo phone/email,
  no known-weak password hash, OAuth2 secrets are per-deployment values.
- Token generation uses `SecureRandom`; password hashing is BCrypt.
- Secrets that must be recoverable at rest use AES-GCM with a random nonce;
  browser-side application encryption never substitutes for TLS.
- Login and SMS endpoints carry `@RateLimiter`; if a registration or other
  anonymous entry point is ever introduced it must carry one too.
- File storage validates canonical paths against traversal for every
  storage backend.
- Log files, `.env`, `docker.env`, and lockfiles with resolved
  credentials never enter the repository. Runtime logs are not
  committed; access logs redact sensitive fields.

## Testing tiers

1. **Unit** (`./mvnw test` / `pnpm test:unit`): service and util logic,
   edge cases, error paths, concurrency. Backend service tests use JUnit 5
   and Mockito; this repository does not provide an H2/BaseDbUnitTest layer.
2. **API/integration**: controller contracts are covered by focused tests;
   `@SpringBootTest` with Testcontainers covers paths that need real MySQL/Redis.
3. **Boot smoke**: the packaged jar starts and answers a health check;
   catches "green unit tests, broken product".
4. **Frontend**: component/logic specs with vitest; e2e snapshots only
   where a harness exists — dead scripts are removed, not kept.

Coverage: repository-wide ratchet, never decreasing; files changed in a
PR are held to the per-file floor. An uncovered line is a candidate for
deletion first, test second. Mock only expensive or non-deterministic
boundaries (external HTTP, clock); keep everything downstream real.
Assert on externally observable state (database rows, files, HTTP
responses), not on the system's self-report.

## CI gates (all blocking, one aggregator check)

Executable topology: `.harness/verify.ps1` on Windows and
`.harness/verify.sh` on Linux CI. Both providers expose the same named gates;
CI-specific infrastructure only supplies tool images, services, caches, and
artifacts. Every blocking job is a dependency of the single `aggregate` check.

Backend: Harness `backend` runs Maven Wrapper `clean verify` — compile,
tests, Spotless, JaCoCo module coverage floors and ArchUnit module boundaries.
Harness `integration` runs `-Pintegration clean verify` and then checks the
backend per-file coverage ratchet against the complete reports.
`dependencies` gate 生成 Maven
解析后的 CycloneDX 聚合 SBOM，并以固定镜像摘要的 Trivy 扫描后端依赖、前端锁文件、
容器配置和最终应用镜像，阻断 HIGH/CRITICAL；该门禁不依赖 NVD API Key。
Frontend: `pnpm check` (circular, dep, explicit-any ratchet, workspace typecheck
registration, typecheck, cspell) + `pnpm lint`
+ `pnpm test:coverage`（单元测试与覆盖率报告）。Harness `frontend` 随后运行
生产构建与产物检查、单文件覆盖率棘轮；单独运行 `test:coverage` 不执行棘轮。
`pnpm-lock.yaml` must be committed. Complete commands and gate scope are listed
in the root README; individual package commands do not replace Harness gates.
Every frontend workspace package with a `tsconfig.json` registers a real
`typecheck` command; packages containing Vue SFC source use `vue-tsc`, and all
other typed packages use `tsc`. The contract gate rejects missing commands and
placeholder bypasses before Turbo runs.
Repo: pre-commit runs trailing-newline/whitespace and the secret scan via
lefthook; staged frontend files pass prettier/eslint/stylelint through
`scripts/pre-commit-frontend.sh`（lefthook 驱动，非 lint-staged 包）;
`node scripts/check-source-quality.mjs` blocks logical source files
over 800 lines and unowned `TODO`/`FIXME`/`XXX` comments; production build uses
`--mode production` only. Package builds must transform TypeScript in Vue SFCs;
the production-build wrapper rejects skipped transformation warnings and
untransformed TypeScript SFCs in `dist`.
Contracts: `node scripts/check-field-catalog.mjs` keeps
`docs/contracts/field-catalog.yaml` (the field-contract source of truth)
in sync with both ends' code; handling rules live in
`docs/security/data-classification.md`.
Starter documentation: `node scripts/check-starter-documentation.mjs` requires every
`basic-framework-spring-boot-starter-*` capability seam to contain a structured README.
Lifecycle: `node scripts/check-data-lifecycle.mjs` keeps every final Flyway
table and physical foreign key registered in
`docs/contracts/data-lifecycle.json`; policy semantics live in
`docs/data-lifecycle.md`.
Data permission: `node scripts/check-data-permission.mjs` requires every
application table to be protected by an explicit runtime dept/user-column
registration or documented in `docs/contracts/data-permission-exemptions.json`
with an alternative control and verifiable production-source evidence; new
unclassified tables and stale exemption evidence fail the contracts gate.
Permission catalog: `node scripts/check-permission-catalog.mjs` requires every permission code referenced by `hasPermission(...)` to be either present in the effective Flyway `system_menu` catalog or registered in `docs/contracts/permission-catalog.json` with a reason and a control; catalog codes with no consuming endpoint and stale ledger entries fail as well.

Security signals: `node scripts/check-security-signals.mjs` requires every counter emitted by
`SecuritySignalMetrics` to be registered in `docs/security/security-signals.md` and referenced by
`ops/prometheus/security-signals.rules.yml` under its scraped `_total` name; stale entries in either
file fail. Adding a signal without an alert threshold and a runbook entry is an incomplete change.

Gate integrity: `node scripts/check-gate-rejection-tests.mjs` enforces the rule at the top of this
document mechanically - every `check-*.mjs` gate must ship a same-named `*.test.mjs` rejection test
that both harness providers execute. `node scripts/check-gate-wiring.mjs` separately forbids orphan
gate scripts and requires the two providers to stay aligned.

Controller validation: `node scripts/check-controller-validation.mjs` keeps
business-module controllers uniform — id-class `@RequestParam`/`@PathVariable`
parameters carry `@Positive`, every `*ReqVO` input carries `@Valid`, and any
controller declaring parameter constraints has class-level `@Validated`.

## Documentation

- One home per fact; every other mention links instead of repeating.
- Docs accompany every behavior change: the owning module's README and
  the code comments update in the same commit.
- Comments state contracts and safe-use facts, not control-flow
  narration. Javadoc on public api-package methods includes
  `@param`/`@return`.
- README files describe what actually exists; removed features are
  removed from docs in the same change.
- Non-trivial changes record a short decision note under `docs/adr/`.

## Code conventions

- CI-enforced source limit: logical source files contain at most 800 lines;
  scope and static-asset treatment are defined by the source-quality contract.
- Review guidance: prefer immutability and focused files, usually 200–400 lines.
  Use complexity ≤ 10, parameters ≤ 5, nesting ≤ 3 and functions ≤ 50 lines as
  review targets. These four metrics currently have no blocking CI gate;
  assess readability and decomposition during review rather than claiming
  that a passing build proves these targets. Promoting a target to a mandatory
  limit requires a tested gate and wiring into the CI aggregate.
- Every user-supplied parameter is validated: normal / boundary / null /
  overflow / special-chars / type-error.
- Closed enum/switch logic ends in a default-throw; unknown values fail,
  never silently pass.
- Empty `catch` blocks name what they swallow and why.
- No dead code: unused scripts, env vars, config keys, and mock
  scaffolding are deleted in the change that orphans them.
- `TODO` (this iteration) / `FIXME` (bug, next release) / `XXX` (known
  tech debt) carry an owner or issue reference; the contracts gate enforces it.
- No comments restating what the code obviously does. No emoji in code,
  comments, or docs.

## Git workflow

- Conventional commits (`feat:` `fix:` `refactor:` `docs:` `test:`),
  small focused commits, local gates green before commit.
- One PR = one purpose; split independent changes. Fix a regression in
  the PR that introduced it before it propagates.
