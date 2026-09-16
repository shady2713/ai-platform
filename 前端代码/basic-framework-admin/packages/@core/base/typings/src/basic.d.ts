interface BasicOption {
  label: string;
  value: string;
}

type SelectOption = BasicOption;

type TabOption = BasicOption;

interface BasicUserInfo {
  /**
   * 头像
   */
  avatar: string;
  /**
   * 用户昵称
   */
  nickname: string;
  /**
   * 用户角色
   */
  roles?: string[];
  /**
   * 用户id
   */
  userId: string;
  /**
   * 用户名
   */
  username: string;
}

/** 管理后台权限接口返回的登录用户信息。 */
interface AdminUserInfo {
  avatar: string;
  deptId: number;
  email?: string;
  id: number;
  nickname: string;
  username: string;
}

type ClassType = Array<object | string> | object | string;

export type {
  AdminUserInfo,
  BasicOption,
  BasicUserInfo,
  ClassType,
  SelectOption,
  TabOption,
};
