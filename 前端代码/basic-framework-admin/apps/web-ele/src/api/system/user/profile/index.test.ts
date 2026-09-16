import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getUserProfile, updateUserPassword, updateUserProfile } from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('user profile API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps profile endpoints', async () => {
    const profile = { email: 'user@example.com', nickname: 'user' };
    const password = { newPassword: 'new-pass', oldPassword: 'old-pass' };

    await getUserProfile();
    await updateUserProfile(profile);
    await updateUserPassword(password);

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/user/profile/get'],
    ]);
    expect(requestClient.put.mock.calls).toEqual([
      ['/system/user/profile/update', profile],
      ['/system/user/profile/update-password', password],
    ]);
  });
});
