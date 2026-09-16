import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SystemNotifyTemplateApi {
  /** 站内信模板 */
  export interface Template {
    id: number;
    name: string;
    code: string;
    nickname: string;
    content: string;
    type: number;
    params: string[];
    status: number;
    remark: string;
    createTime?: string;
  }
}

/** 查询站内信模板列表 */
export function getNotifyTemplatePage(params: PageParam) {
  return requestClient.get<PageResult<SystemNotifyTemplateApi.Template>>(
    '/system/notify-template/page',
    { params },
  );
}

/** 查询站内信模板详情 */
export function getNotifyTemplate(id: number) {
  return requestClient.get<SystemNotifyTemplateApi.Template>(
    `/system/notify-template/get?id=${id}`,
  );
}

/** 新增站内信模板 */
export function createNotifyTemplate(data: SystemNotifyTemplateApi.Template) {
  return requestClient.post('/system/notify-template/create', data);
}

/** 修改站内信模板 */
export function updateNotifyTemplate(data: SystemNotifyTemplateApi.Template) {
  return requestClient.put('/system/notify-template/update', data);
}

/** 删除站内信模板 */
export function deleteNotifyTemplate(id: number) {
  return requestClient.delete(`/system/notify-template/delete?id=${id}`);
}
