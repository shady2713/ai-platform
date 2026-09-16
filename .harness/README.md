# basic-framework verification harness

This directory adapts the DeepSeek Harness engineering model to this repository.
It is not an upstream DeepSeek Harness runtime profile or a claim that upstream
defines a `.harness` file format.

The adaptation keeps four properties from the upstream model:

1. scoped `AGENTS.md` instructions carry durable engineering constraints;
2. capabilities are consumed through explicit seams rather than core patches;
3. mechanizable invariants are executable and blocking;
4. verification evidence matches the changed boundary.

The repository-root `AGENTS.md` remains the authority for engineering rules.
`verify.ps1` and `verify.sh` are platform providers for one named gate
topology: Windows development uses PowerShell and Linux CI uses POSIX `sh`.
The `dependencies` gate generates a resolved backend CycloneDX SBOM and scans it
together with the exact frontend lockfile, container configuration, and final
application image. Its Trivy container is pinned by version and digest, receives
source inputs read-only, and runs without added Linux capabilities.

```powershell
& .\.harness\verify.ps1 --list
```

```sh
sh .harness/verify.sh --list
```

## Durable and runtime boundary

Tracked harness files contain commands and scoped maintenance instructions only.
Credentials, `.env` files, container state, test reports, coverage output, logs,
and generated artifacts are runtime state and must stay outside `.harness/`.

## Change protocol

When adding or changing a gate:

1. place the rule in its authoritative document;
2. make a named gate reject a representative violation;
3. restore the valid state and record a green run;
4. wire the gate into the blocking CI aggregate;
5. update the repository README only when the developer entrypoint changes.
