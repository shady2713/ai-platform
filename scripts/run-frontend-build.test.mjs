import assert from "node:assert/strict";
import test from "node:test";

import {
  containsTypeScriptVueScriptBlock,
  containsTypeScriptBuildError,
  containsUntransformedVueSfcWarning,
  isBuiltTestArtifact,
} from "./run-frontend-build.mjs";

test("detects declaration diagnostics even when the build reports success", () => {
  const output = [
    "src/example.ts(1,1): error TS4058: Return type cannot be named.",
    "Build succeeded",
  ].join("\n");

  assert.equal(containsTypeScriptBuildError(output), true);
});

test("ignores ordinary build output and ANSI formatting", () => {
  assert.equal(
    containsTypeScriptBuildError("\u001B[32mBuild succeeded\u001B[39m"),
    false,
  );
});

test("detects skipped Vue SFC TypeScript transformation", () => {
  const warning = [
    "[mkdist] vue-sfc-transformer is not installed.",
    "mkdist will not transform typescript syntax in Vue SFCs.",
  ].join("\n");

  assert.equal(containsUntransformedVueSfcWarning(warning), true);
  assert.equal(
    containsUntransformedVueSfcWarning("\u001B[32mBuild succeeded\u001B[39m"),
    false,
  );
});

test("recognizes TypeScript Vue script blocks in build artifacts", () => {
  assert.equal(
    containsTypeScriptVueScriptBlock('<script setup lang="ts">'),
    true,
  );
  assert.equal(
    containsTypeScriptVueScriptBlock("<script lang='tsx' setup>"),
    true,
  );
  assert.equal(containsTypeScriptVueScriptBlock("<script setup>"), false);
});

test("recognizes test files and test directories in package output", () => {
  assert.equal(isBuiltTestArtifact("dist/example.test.mjs"), true);
  assert.equal(isBuiltTestArtifact("dist/example.spec.d.ts"), true);
  assert.equal(isBuiltTestArtifact("dist/__tests__/example.mjs"), true);
  assert.equal(isBuiltTestArtifact("dist/example.mjs"), false);
});
