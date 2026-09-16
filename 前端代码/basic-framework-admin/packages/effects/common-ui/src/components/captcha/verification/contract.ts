import type {
  CaptchaChallenge,
  CaptchaCheckRequest,
  CaptchaProtocolResponse,
  CaptchaType,
} from '@vben/types';

interface ParsedCaptchaResponse<T> {
  code: string;
  data?: T;
  message?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseEnvelope(
  response: unknown,
): CaptchaProtocolResponse<unknown> | undefined {
  if (!isRecord(response) || !isRecord(response.data)) {
    return undefined;
  }
  const { repCode, repData, repMsg } = response.data;
  if (typeof repCode !== 'string') {
    return undefined;
  }
  return {
    repCode,
    ...(repData === undefined ? {} : { repData }),
    ...(typeof repMsg === 'string' ? { repMsg } : {}),
  };
}

function hasStringArray(value: unknown): value is string[] {
  return (
    Array.isArray(value) &&
    value.length > 0 &&
    value.every((item) => typeof item === 'string')
  );
}

function parseChallenge(
  value: unknown,
  captchaType: CaptchaType,
): CaptchaChallenge | undefined {
  if (
    !isRecord(value) ||
    typeof value.originalImageBase64 !== 'string' ||
    typeof value.token !== 'string'
  ) {
    return undefined;
  }
  if (
    captchaType === 'blockPuzzle' &&
    typeof value.jigsawImageBase64 !== 'string'
  ) {
    return undefined;
  }
  if (captchaType === 'clickWord' && !hasStringArray(value.wordList)) {
    return undefined;
  }
  return {
    originalImageBase64: value.originalImageBase64,
    token: value.token,
    ...(typeof value.jigsawImageBase64 === 'string'
      ? { jigsawImageBase64: value.jigsawImageBase64 }
      : {}),
    ...(hasStringArray(value.wordList) ? { wordList: value.wordList } : {}),
  };
}

export function createCaptchaCheckRequest(
  captchaType: CaptchaType,
  token: string,
  pointJson: string,
): CaptchaCheckRequest {
  return { captchaType, pointJson, token };
}

export function createCaptchaVerification(
  token: string,
  pointJson: string,
): string {
  return `${token}---${pointJson}`;
}

export function parseCaptchaResponse(
  response: unknown,
): ParsedCaptchaResponse<unknown> | undefined {
  const envelope = parseEnvelope(response);
  if (!envelope) return undefined;
  return {
    code: envelope.repCode,
    ...(envelope.repData === undefined ? {} : { data: envelope.repData }),
    ...(envelope.repMsg === undefined ? {} : { message: envelope.repMsg }),
  };
}

export function parseCaptchaChallengeResponse(
  response: unknown,
  captchaType: CaptchaType,
): ParsedCaptchaResponse<CaptchaChallenge> | undefined {
  const parsed = parseCaptchaResponse(response);
  if (!parsed) return undefined;
  if (parsed.code !== '0000') {
    return { code: parsed.code, message: parsed.message };
  }
  const challenge = parseChallenge(parsed.data, captchaType);
  return challenge ? { code: parsed.code, data: challenge } : undefined;
}
