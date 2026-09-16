import type { CaptchaCheckApi, CaptchaGetApi, CaptchaType } from '@vben/types';

interface VerificationProps {
  arith?: number;
  barSize?: {
    height: string;
    width: string;
  };
  blockSize?: {
    height: string;
    width: string;
  };
  captchaType?: CaptchaType;
  explain?: string;
  figure?: number;
  imgSize?: {
    height: string;
    width: string;
  };
  mode?: 'fixed' | 'pop';
  space?: number;
  type?: '1' | '2';
  checkCaptchaApi?: CaptchaCheckApi;
  getCaptchaApi?: CaptchaGetApi;
}

export type { VerificationProps };
