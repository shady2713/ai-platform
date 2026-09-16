import { checkFileType } from '@vben/utils';

const SAFE_IMAGE_DATA_URL =
  /^data:image\/(?:bmp|gif|jpeg|png|webp);base64,[a-z\d+/]+=*$/i;
const SAFE_URL_PROTOCOLS = new Set(['blob:', 'http:', 'https:']);

export function assertUploadConfiguration(maxNumber: number, maxSize: number) {
  const validNumber =
    maxNumber === Infinity ||
    (Number.isSafeInteger(maxNumber) && maxNumber > 0);
  if (!validNumber) {
    throw new Error('maxNumber 必须是正整数或 Infinity');
  }
  if (!Number.isFinite(maxSize) || maxSize <= 0) {
    throw new Error('maxSize 必须是大于 0 的有限数值');
  }
}

export function isAllowedUploadFile(
  file: File,
  accepts: string[],
  imageOnly: boolean,
): boolean {
  if (!checkFileType(file, accepts)) return false;
  return !imageOnly || !file.type || file.type.startsWith('image/');
}

export function isSafeUploadUrl(
  value: string,
  allowImageData = false,
): boolean {
  const url = value.trim();
  if (!url) return false;
  if (allowImageData && SAFE_IMAGE_DATA_URL.test(url)) return true;
  try {
    return SAFE_URL_PROTOCOLS.has(
      new URL(url, globalThis.location?.origin ?? 'http://localhost').protocol,
    );
  } catch {
    return false;
  }
}
