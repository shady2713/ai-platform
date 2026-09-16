import type { AdminUserInfo, AppRouteRecordRaw } from '@vben-core/typings';

type UserInfo = AdminUserInfo;

/** 认证权限信息，包含用户、角色、权限码和菜单 */
interface AuthPermissionInfo {
  user: UserInfo;
  roles: string[];
  permissions: string[];
  menus: AppRouteRecordRaw[];
}

export type { AuthPermissionInfo, UserInfo };
