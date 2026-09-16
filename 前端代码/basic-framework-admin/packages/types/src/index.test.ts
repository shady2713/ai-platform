import type { AuthPermissionInfo, CaptchaGetRequest, UserInfo } from './index';

import { describe, expect, it } from 'vitest';

// Side-effect imports execute the type-only contract modules.
import './user';
import './index';

describe('types package contracts', () => {
  it('authPermissionInfo bundles user, roles, permissions and menus', () => {
    const user: UserInfo = {
      avatar: '',
      deptId: 1,
      id: 1,
      nickname: 'Admin',
      username: 'admin',
    };
    const info: AuthPermissionInfo = {
      menus: [{ id: 1, name: 'System', parentId: 0, path: '/system' }],
      permissions: ['system:user:query'],
      roles: ['super_admin'],
      user,
    };
    expect(info.user.username).toBe('admin');
    expect(info.roles).toEqual(['super_admin']);
    expect(info.permissions).toContain('system:user:query');
    expect(info.menus[0]?.name).toBe('System');
  });

  it('captcha contracts type the challenge request payload', () => {
    const request: CaptchaGetRequest = { captchaType: 'blockPuzzle' };
    expect(request.captchaType).toBe('blockPuzzle');
  });
});
