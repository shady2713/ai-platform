# AGENTS.md — verification harness scope

Rules in this directory supplement the repository-root `AGENTS.md`; they do not
replace or repeat product, architecture, security, or data contracts.

- `.harness/` owns executable verification topology only. Product rules remain
  in the root `AGENTS.md`, and domain facts remain in their owning documents.
- `verify.ps1` and `verify.sh` are platform providers for the same gate
  contract. Keep their gate names, command arguments, ordering, and failure
  semantics aligned. PowerShell is the Windows entrypoint; POSIX `sh` is the
  Linux CI entrypoint.
- A new mechanizable rule is incomplete until a named gate can fail for a
  representative violation and the blocking CI aggregate depends on that gate.
- Match evidence to the boundary. Database, cache, migration, and packaged-jar
  behavior stays on the real Testcontainers integration path. A Spring test
  context may use real containers; packaged-jar startup must launch the built
  artifact. Mocks or a context-only startup do not verify those runtime boundaries.
- Never persist credentials, container identifiers, ports, logs, reports, or
  other runtime state here. The harness stores durable commands only.
- Changes to a gate require focused red/green evidence for the gate itself and
  a successful harness validation before handoff.
