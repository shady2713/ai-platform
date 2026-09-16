import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SystemSessionApi {
  export interface UserSession {
    id: number;
    userId: number;
    userType: number;
    createTime?: Date;
    accessExpiresTime?: Date;
    refreshExpiresTime?: Date;
  }
}

export function getSessionPage(params: PageParam) {
  return requestClient.get<PageResult<SystemSessionApi.UserSession>>(
    '/system/session/page',
    { params },
  );
}

export function revokeSession(id: number) {
  return requestClient.delete(`/system/session/revoke?id=${id}`);
}

export function revokeSessionList(ids: number[]) {
  return requestClient.delete(
    `/system/session/revoke-list?ids=${ids.join(',')}`,
  );
}
