import {
  createRouter,
  createWebHashHistory,
  createWebHistory,
} from 'vue-router';

import { createRouterGuard } from './guard';
import { routes } from './routes';

interface SavedScrollPosition {
  left: number;
  top: number;
}

function resolveScrollPosition(
  hash: string,
  savedPosition: null | SavedScrollPosition,
) {
  if (savedPosition) {
    return savedPosition;
  }
  return hash ? { behavior: 'smooth' as const, el: hash } : { left: 0, top: 0 };
}

/**
 *  @zh_CN 创建vue-router实例
 */
const router = createRouter({
  history:
    import.meta.env.VITE_ROUTER_HISTORY === 'hash'
      ? createWebHashHistory(import.meta.env.VITE_BASE)
      : createWebHistory(import.meta.env.VITE_BASE),
  // 应该添加到路由的初始路由列表。
  routes,
  scrollBehavior: (to, _from, savedPosition) => {
    return resolveScrollPosition(to.hash, savedPosition);
  },
  // 是否应该禁止尾部斜杠。
  // strict: true,
});

// 创建路由守卫
createRouterGuard(router);
export { resolveScrollPosition, router };
