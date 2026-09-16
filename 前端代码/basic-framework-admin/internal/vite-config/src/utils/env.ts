import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

type EnvValue = string | undefined;

const getBoolean = (key: string, value: EnvValue) => {
  if (value === undefined || value === 'false') {
    return false;
  }
  if (value === 'true') {
    return true;
  }
  throw new TypeError(`${key} 必须为 true 或 false`);
};

const getPort = (value: EnvValue, fallback: number) => {
  if (value === undefined) {
    return fallback;
  }
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new TypeError('VITE_PORT 必须为 1-65535 的整数');
  }
  return port;
};
const getString = (value: EnvValue, fallback: string) => value ?? fallback;

function parseEnvContent(content: string) {
  const parsed: Record<string, string> = {};
  const lines = content.split(/\r?\n/);
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      continue;
    }
    const separatorIndex = line.indexOf('=');
    if (separatorIndex <= 0) {
      continue;
    }
    const key = line.slice(0, separatorIndex).trim();
    if (!/^[a-z_]\w*$/i.test(key)) {
      continue;
    }
    let value = line.slice(separatorIndex + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    parsed[key] = value;
  }
  return parsed;
}

async function readEnvFiles(root: string, mode?: string) {
  const files = ['.env', '.env.local'];
  if (mode) {
    files.push(`.env.${mode}`, `.env.${mode}.local`);
  }

  const envConfig: Record<string, string> = {};
  for (const file of files) {
    const fullPath = join(root, file);
    if (!existsSync(fullPath)) {
      continue;
    }
    const content = await readFile(fullPath, 'utf8');
    Object.assign(envConfig, parseEnvContent(content));
  }
  return envConfig;
}

async function loadEnv(root: string, mode?: string, extraFiles?: string[]) {
  const envConfig = await readEnvFiles(root, mode);
  if (extraFiles?.length) {
    for (const file of extraFiles) {
      if (!existsSync(file)) {
        continue;
      }
      const content = await readFile(file, 'utf8');
      Object.assign(envConfig, parseEnvContent(content));
    }
  }
  return envConfig;
}

async function loadAndConvertEnv(root: string, mode?: string) {
  const env = await loadEnv(root, mode);
  return {
    ...env,
    VITE_VISUALIZER: getBoolean('VITE_VISUALIZER', env.VITE_VISUALIZER),
    base: getString(env.VITE_BASE, '/'),
    port: getPort(env.VITE_PORT, 5173),
  };
}

export { loadAndConvertEnv, loadEnv };
