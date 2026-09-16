import { describe, expect, it } from 'vitest';

import {
  createCaptchaCheckRequest,
  createCaptchaVerification,
  parseCaptchaChallengeResponse,
  parseCaptchaResponse,
} from './contract';

function response(repCode: unknown, repData?: unknown, repMsg?: unknown) {
  return { data: { repCode, repData, repMsg } };
}

describe('captcha response contract', () => {
  it('builds the TLS-only plaintext captcha protocol payload', () => {
    const pointJson = JSON.stringify({ x: 18, y: 5 });

    expect(
      createCaptchaCheckRequest('blockPuzzle', 'one-time-token', pointJson),
    ).toEqual({
      captchaType: 'blockPuzzle',
      pointJson,
      token: 'one-time-token',
    });
    expect(createCaptchaVerification('one-time-token', pointJson)).toBe(
      `one-time-token---${pointJson}`,
    );
  });

  it('parses a block-puzzle challenge', () => {
    expect(
      parseCaptchaChallengeResponse(
        response('0000', {
          jigsawImageBase64: 'jigsaw',
          originalImageBase64: 'original',
          secretKey: 'legacy-secret-that-must-not-propagate',
          token: 'token',
        }),
        'blockPuzzle',
      ),
    ).toEqual({
      code: '0000',
      data: {
        jigsawImageBase64: 'jigsaw',
        originalImageBase64: 'original',
        token: 'token',
      },
    });
  });

  it('requires a non-empty word list for a click-word challenge', () => {
    expect(
      parseCaptchaChallengeResponse(
        response('0000', {
          originalImageBase64: 'original',
          token: 'token',
          wordList: ['春', '风'],
        }),
        'clickWord',
      )?.data?.wordList,
    ).toEqual(['春', '风']);

    expect(
      parseCaptchaChallengeResponse(
        response('0000', {
          originalImageBase64: 'original',
          token: 'token',
          wordList: [],
        }),
        'clickWord',
      ),
    ).toBeUndefined();
  });

  it('preserves a protocol rejection without requiring challenge data', () => {
    expect(
      parseCaptchaChallengeResponse(
        response('FAIL', undefined, 'verification rejected'),
        'blockPuzzle',
      ),
    ).toEqual({ code: 'FAIL', message: 'verification rejected' });
  });

  it('rejects malformed envelopes and challenges', () => {
    expect(parseCaptchaResponse(undefined)).toBeUndefined();
    expect(parseCaptchaResponse({ data: { repCode: 0 } })).toBeUndefined();
    expect(
      parseCaptchaChallengeResponse(
        response('0000', {
          originalImageBase64: 'original',
          token: 'token',
        }),
        'blockPuzzle',
      ),
    ).toBeUndefined();
    expect(
      parseCaptchaChallengeResponse(
        response('0000', {
          jigsawImageBase64: 'jigsaw',
          originalImageBase64: 'original',
          token: 123,
        }),
        'blockPuzzle',
      ),
    ).toBeUndefined();
  });
});
