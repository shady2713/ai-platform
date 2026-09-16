import { readdir, readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const requireLint = createRequire(
  join(scriptDirectory, '../internal/lint-configs/eslint-config/package.json'),
);
const { Linter } = requireLint('eslint');
const globals = requireLint('globals');
const linter = new Linter();

// Universal dependencies probe other runtimes; these names are not browser polyfills.
const runtimeProbes = Object.fromEntries(
  ['Buffer', 'exports', 'global', 'module', 'process', 'setImmediate'].map(
    (name) => [name, 'readonly'],
  ),
);
// Vue I18n initializes these optional compilation flags on the global object.
const featureFlags = Object.fromEntries(
  [
    '__INTLIFY_DROP_MESSAGE_COMPILER__',
    '__INTLIFY_PROD_DEVTOOLS__',
    '__VUE_I18N_FULL_INSTALL__',
    '__VUE_I18N_LEGACY_API__',
  ].map((name) => [name, 'readonly']),
);

export function inspectBuiltJavaScript(source) {
  return linter.verify(source, {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.worker,
        ...runtimeProbes,
        ...featureFlags,
      },
    },
    linterOptions: { noInlineConfig: true },
    rules: { 'no-undef': ['error', { typeof: true }] },
  });
}

export async function checkBuiltJavaScript(
  directory = join(scriptDirectory, '../apps/web-ele/dist'),
) {
  const failures = [];
  let files = 0;
  async function visit(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) {
        await visit(path);
      } else if (entry.name.endsWith('.js')) {
        files += 1;
        for (const message of inspectBuiltJavaScript(
          await readFile(path, 'utf8'),
        )) {
          failures.push(
            `${path}:${message.line}:${message.column} ${message.message}`,
          );
        }
      }
    }
  }
  await visit(directory);
  if (files === 0 || failures.length > 0) {
    throw new Error(
      `Production JavaScript validation failed (${files} files):\n${failures.join('\n')}`,
    );
  }
  console.info(
    `Production JavaScript: ${files} files passed no-undef validation`,
  );
}
