const DIGEST_BYTES = 32;
const ITERATIONS = 210_000;
const MAX_PASSWORD_LENGTH = 128;
const SALT_BYTES = 16;

export interface LockScreenCredential {
  digest: string;
  iterations: number;
  salt: string;
}

export async function createLockScreenCredential(
  password: string,
): Promise<LockScreenCredential> {
  validatePassword(password);
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  const digest = await deriveDigest(password, salt, ITERATIONS);
  return {
    digest: bytesToHex(digest),
    iterations: ITERATIONS,
    salt: bytesToHex(salt),
  };
}

export async function verifyLockScreenCredential(
  password: string,
  credential: LockScreenCredential | undefined,
): Promise<boolean> {
  if (!credential || !isCredentialShapeValid(credential)) {
    return false;
  }
  try {
    validatePassword(password);
    const actual = await deriveDigest(
      password,
      hexToBytes(credential.salt),
      credential.iterations,
    );
    return constantTimeEqual(actual, hexToBytes(credential.digest));
  } catch {
    return false;
  }
}

async function deriveDigest(
  password: string,
  salt: Uint8Array<ArrayBuffer>,
  iterations: number,
): Promise<Uint8Array> {
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(password),
    'PBKDF2',
    false,
    ['deriveBits'],
  );
  const bits = await crypto.subtle.deriveBits(
    { hash: 'SHA-256', iterations, name: 'PBKDF2', salt },
    keyMaterial,
    DIGEST_BYTES * 8,
  );
  return new Uint8Array(bits);
}

function validatePassword(password: string) {
  if (
    typeof password !== 'string' ||
    password.length === 0 ||
    password.length > MAX_PASSWORD_LENGTH
  ) {
    throw new TypeError('锁屏口令长度必须在 1 到 128 个字符之间');
  }
}

function isCredentialShapeValid(credential: LockScreenCredential) {
  return (
    credential.iterations === ITERATIONS &&
    isHexWithByteLength(credential.salt, SALT_BYTES) &&
    isHexWithByteLength(credential.digest, DIGEST_BYTES)
  );
}

function isHexWithByteLength(value: string, byteLength: number) {
  return value.length === byteLength * 2 && /^[\da-f]+$/i.test(value);
}

function bytesToHex(bytes: Uint8Array) {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join(
    '',
  );
}

function hexToBytes(value: string) {
  return Uint8Array.from(value.match(/.{2}/g) ?? [], (part) =>
    Number.parseInt(part, 16),
  );
}

function constantTimeEqual(left: Uint8Array, right: Uint8Array) {
  if (left.length !== right.length) {
    return false;
  }
  let difference = 0;
  let index = 0;
  for (const value of left) {
    difference |= value ^ (right[index] ?? 0);
    index += 1;
  }
  return difference === 0;
}
