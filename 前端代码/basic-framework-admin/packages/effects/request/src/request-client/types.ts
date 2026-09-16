import type {
  AxiosRequestConfig,
  AxiosResponse,
  CreateAxiosDefaults,
  InternalAxiosRequestConfig,
} from 'axios';

type ErrorMode = 'global' | 'inline' | 'silent';

type ExtendOptions<T = unknown> = {
  /**
   * Authentication retries stay bound to their renewed token.
   * @internal
   */
  __isRetryRequest?: boolean;
  /**
   * 错误消费方式。
   * - global: 由全局拦截器提示（默认）。
   * - inline: 调用方在表单或组件内展示。
   * - silent: 调用方静默处理，不展示反馈。
   */
  errorMode?: ErrorMode;
  /**
   * 参数序列化方式。预置的有
   * - brackets: ids[]=1&ids[]=2&ids[]=3
   * - comma: ids=1,2,3
   * - indices: ids[0]=1&ids[1]=2&ids[2]=3
   * - repeat: ids=1&ids=2&ids=3
   */
  paramsSerializer?:
    | 'brackets'
    | 'comma'
    | 'indices'
    | 'repeat'
    | AxiosRequestConfig<T>['paramsSerializer'];
  /**
   * 响应数据的返回方式。
   * - raw: 原始的AxiosResponse，包括headers、status等，不做是否成功请求的检查。
   * - body: 返回响应数据的BODY部分（只会根据status检查请求是否成功，忽略对code的判断，这种情况下应由调用方检查请求是否成功）。
   * - data: 解构响应的BODY数据，只返回其中的data节点数据（会检查status和code是否为成功状态）。
   */
  responseReturn?: 'body' | 'data' | 'raw';
};
type RequestClientConfig<T = unknown> = AxiosRequestConfig<T> &
  ExtendOptions<T>;

type RequestResponse<T = unknown> = AxiosResponse<T> & {
  config: RequestClientConfig<T>;
};

type RequestContentType =
  | 'application/json;charset=utf-8'
  | 'application/octet-stream;charset=utf-8'
  | 'application/x-www-form-urlencoded;charset=utf-8'
  | 'multipart/form-data;charset=utf-8';

type RequestClientOptions = CreateAxiosDefaults<unknown> &
  ExtendOptions<unknown>;

type InterceptorRequestConfig = ExtendOptions<unknown> &
  InternalAxiosRequestConfig<unknown>;

/**
 * SSE 请求选项
 */
interface SseRequestOptions extends RequestInit {
  onMessage?: (message: string) => void;
  onEnd?: () => void;
}

interface RequestInterceptorConfig {
  fulfilled?: (
    config: InterceptorRequestConfig,
  ) => InterceptorRequestConfig | Promise<InterceptorRequestConfig>;
  rejected?: (error: unknown) => unknown;
}

interface ResponseInterceptorConfig<T = unknown> {
  fulfilled?: (response: RequestResponse<T>) => Promise<unknown> | unknown;
  rejected?: (error: unknown) => unknown;
}

type MakeErrorMessageFn = (message: string, error: unknown) => void;

interface HttpResponse<T = unknown> {
  /**
   * 0 表示成功 其他表示失败
   * 0 means success, others means fail
   */
  code: number;
  data: T;
  msg: string;
}

interface PageParam {
  [key: string]: unknown;
  pageNo: number;
  pageSize: number;
}

interface PageResult<T> {
  list: T[];
  total: number;
}

export type {
  ErrorMode,
  HttpResponse,
  InterceptorRequestConfig,
  MakeErrorMessageFn,
  PageParam,
  PageResult,
  RequestClientConfig,
  RequestClientOptions,
  RequestContentType,
  RequestInterceptorConfig,
  RequestResponse,
  ResponseInterceptorConfig,
  SseRequestOptions,
};
