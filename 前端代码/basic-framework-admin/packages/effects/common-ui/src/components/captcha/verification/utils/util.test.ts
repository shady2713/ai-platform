import { afterEach, describe, expect, it } from 'vitest';

import { resetSize, scaleCaptchaPoints } from './util';

describe('captcha size utilities', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('resolves percentages from the containing element', () => {
    const parent = document.createElement('div');
    const root = document.createElement('div');
    Object.defineProperties(parent, {
      offsetHeight: { value: 400 },
      offsetWidth: { value: 800 },
    });
    parent.append(root);
    document.body.append(parent);

    expect(
      resetSize(
        root,
        { height: '10%', width: '50%' },
        { height: '155px', width: '25%' },
      ),
    ).toEqual({
      barHeight: '40px',
      barWidth: '400px',
      imgHeight: '155px',
      imgWidth: '200px',
    });
  });

  it('falls back to viewport dimensions and handles invalid percentages', () => {
    expect(
      resetSize(
        null,
        { height: 'invalid%', width: '100%' },
        { height: '20px', width: '310px' },
      ),
    ).toEqual({
      barHeight: '0px',
      barWidth: `${window.innerWidth}px`,
      imgHeight: '20px',
      imgWidth: '310px',
    });
  });

  it('scales valid points to the server coordinate system', () => {
    expect(
      scaleCaptchaPoints(
        [
          { x: 0, y: 0 },
          { x: 100, y: 50 },
        ],
        { imgHeight: '100px', imgWidth: '200px' },
      ),
    ).toEqual([
      { x: 0, y: 0 },
      { x: 155, y: 78 },
    ]);
  });

  it('rejects invalid dimensions and out-of-bounds points', () => {
    expect(
      scaleCaptchaPoints([{ x: 1, y: 1 }], {
        imgHeight: '0px',
        imgWidth: '200px',
      }),
    ).toBeUndefined();
    expect(
      scaleCaptchaPoints([{ x: 201, y: 1 }], {
        imgHeight: '100px',
        imgWidth: '200px',
      }),
    ).toBeUndefined();
    expect(
      scaleCaptchaPoints([{ x: Number.NaN, y: 1 }], {
        imgHeight: '100px',
        imgWidth: '200px',
      }),
    ).toBeUndefined();
  });
});
