import { spawn } from "node:child_process";
import { readFile, readdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const FRONTEND_ROOT = join(REPO_ROOT, "前端代码", "basic-framework-admin");
const ANSI_ESCAPE_PATTERN = /\u001B\[[0-?]*[ -/]*[@-~]/g;
const TYPESCRIPT_ERROR_PATTERN = /\berror TS\d+:/i;
const UNTRANSFORMED_VUE_SFC_WARNING_PATTERN =
  /vue-sfc-transformer is not installed[\s\S]*will not transform typescript syntax in Vue SFCs/i;
const TYPESCRIPT_VUE_SCRIPT_PATTERN =
  /<script\b[^>]*\blang\s*=\s*["']tsx?["'][^>]*>/i;
const TEST_ARTIFACT_PATTERN = /\.(?:spec|test)\.(?:d\.ts|[cm]?[jt]sx?)$/i;

export function containsTypeScriptBuildError(output) {
  return TYPESCRIPT_ERROR_PATTERN.test(output.replace(ANSI_ESCAPE_PATTERN, ""));
}

export function containsUntransformedVueSfcWarning(output) {
  return UNTRANSFORMED_VUE_SFC_WARNING_PATTERN.test(
    output.replace(ANSI_ESCAPE_PATTERN, ""),
  );
}

export function containsTypeScriptVueScriptBlock(source) {
  return TYPESCRIPT_VUE_SCRIPT_PATTERN.test(source);
}

export function isBuiltTestArtifact(filePath) {
  const normalizedPath = filePath.replaceAll("\\", "/");
  return (
    normalizedPath.split("/").includes("__tests__") ||
    TEST_ARTIFACT_PATTERN.test(normalizedPath)
  );
}

export async function findBuiltTestArtifacts(
  root = join(FRONTEND_ROOT, "packages"),
) {
  const artifacts = [];

  async function visit(directory, insideDistribution = false) {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.name === "node_modules") continue;

      const entryPath = join(directory, entry.name);
      if (entry.isDirectory()) {
        await visit(entryPath, insideDistribution || entry.name === "dist");
      } else if (insideDistribution && isBuiltTestArtifact(entryPath)) {
        artifacts.push(entryPath);
      }
    }
  }

  await visit(root);
  return artifacts.sort();
}

export async function findUntransformedVueSfcArtifacts(
  root = join(FRONTEND_ROOT, "packages"),
) {
  const artifacts = [];

  async function visit(directory, insideDistribution = false) {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.name === "node_modules") continue;

      const entryPath = join(directory, entry.name);
      if (entry.isDirectory()) {
        await visit(entryPath, insideDistribution || entry.name === "dist");
      } else if (
        insideDistribution &&
        entry.name.endsWith(".vue") &&
        containsTypeScriptVueScriptBlock(await readFile(entryPath, "utf8"))
      ) {
        artifacts.push(entryPath);
      }
    }
  }

  await visit(root);
  return artifacts.sort();
}

export async function runFrontendBuild() {
  const windows = process.platform === "win32";
  const executable = windows ? (process.env.ComSpec ?? "cmd.exe") : "pnpm";
  const arguments_ = windows
    ? ["/d", "/s", "/c", "pnpm.cmd run build:ele"]
    : ["run", "build:ele"];
  const child = spawn(executable, arguments_, {
    cwd: FRONTEND_ROOT,
    env: process.env,
    stdio: ["inherit", "pipe", "pipe"],
  });
  let output = "";

  child.stdout.on("data", (chunk) => {
    const text = chunk.toString();
    output += text;
    process.stdout.write(text);
  });
  child.stderr.on("data", (chunk) => {
    const text = chunk.toString();
    output += text;
    process.stderr.write(text);
  });

  const exitCode = await new Promise((resolveExit, reject) => {
    child.once("error", reject);
    child.once("close", (code) => resolveExit(code ?? 1));
  });
  if (exitCode !== 0) {
    throw new Error(`frontend production build exited with code ${exitCode}`);
  }
  if (containsTypeScriptBuildError(output)) {
    throw new Error(
      "frontend production build emitted a TypeScript declaration error",
    );
  }
  if (containsUntransformedVueSfcWarning(output)) {
    throw new Error(
      "frontend package build skipped TypeScript transformation in Vue SFCs",
    );
  }
  const builtTestArtifacts = await findBuiltTestArtifacts();
  if (builtTestArtifacts.length > 0) {
    throw new Error(
      `frontend package build emitted test artifacts:\n${builtTestArtifacts.join("\n")}`,
    );
  }
  const untransformedVueSfcArtifacts =
    await findUntransformedVueSfcArtifacts();
  if (untransformedVueSfcArtifacts.length > 0) {
    throw new Error(
      `frontend package build emitted untransformed TypeScript Vue SFCs:\n${untransformedVueSfcArtifacts.join("\n")}`,
    );
  }
  const { checkBuiltJavaScript } = await import(
    "../前端代码/basic-framework-admin/scripts/check-built-javascript.mjs"
  );
  await checkBuiltJavaScript();
}

const entryPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (entryPath === fileURLToPath(import.meta.url)) {
  try {
    await runFrontendBuild();
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}
