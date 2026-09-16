export type CaptchaType = 'blockPuzzle' | 'clickWord';

export interface CaptchaGetRequest {
  captchaType: CaptchaType;
}

export interface CaptchaCheckRequest extends CaptchaGetRequest {
  pointJson: string;
  token: string;
}

export interface CaptchaChallenge {
  jigsawImageBase64?: string;
  originalImageBase64: string;
  token: string;
  wordList?: string[];
}

export interface CaptchaPoint {
  x: number;
  y: number;
}

export interface CaptchaProtocolResponse<T = unknown> {
  repCode: string;
  repData?: T;
  repMsg?: string;
}

export interface CaptchaHttpResponse<T = unknown> {
  data: CaptchaProtocolResponse<T>;
}

export interface CaptchaSuccessPayload {
  captchaVerification: string;
}

export type CaptchaGetApi = (
  data: CaptchaGetRequest,
) => Promise<CaptchaHttpResponse<CaptchaChallenge>>;

export type CaptchaCheckApi = (
  data: CaptchaCheckRequest,
) => Promise<CaptchaHttpResponse>;
