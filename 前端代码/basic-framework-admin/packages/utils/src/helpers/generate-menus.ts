import type {
  Router,
  RouteRecordRaw,
  RouteRecordRedirectOption,
} from 'vue-router';

import type {
  AppRouteRecordRaw,
  ExRouteRecordRaw,
  MenuRecordRaw,
  RouteMeta,
  RouteRecordStringComponent,
} from '@vben-core/typings';

import {
  filterTree,
  isHttpUrl,
  mapTree,
  sortTree,
} from '@vben-core/shared/utils';

/**
 * 根据 routes 生成菜单列表
 * @param routes - 路由配置列表
 * @param router - Vue Router 实例
 * @returns 生成的菜单列表
 */
function generateMenus(
  routes: RouteRecordRaw[],
  router: Router,
): MenuRecordRaw[] {
  // 将路由列表转换为一个以 name 为键的对象映射
  const finalRoutesMap: { [key: string]: string } = Object.fromEntries(
    router.getRoutes().map(({ name, path }) => [name, path]),
  );

  let menus = mapTree<ExRouteRecordRaw, MenuRecordRaw>(routes, (route) => {
    // 获取最终的路由路径
    const path = finalRoutesMap[route.name as string] ?? route.path ?? '';

    const { name: routeName, redirect, children = [] } = route;
    const meta = (route.meta ?? {}) as unknown as Partial<RouteMeta>;
    const {
      activeIcon,
      badge,
      badgeType,
      badgeVariants,
      hideChildrenInMenu = false,
      icon,
      link,
      order,
      title = '',
    } = meta;

    // 确保菜单名称不为空
    const name = (title || routeName || '') as string;

    // 处理子菜单
    const resultChildren = hideChildrenInMenu
      ? []
      : ((children as MenuRecordRaw[]) ?? []);

    // 设置子菜单的父子关系
    if (resultChildren.length > 0) {
      resultChildren.forEach((child) => {
        child.parents = [...(route.parents ?? []), path];
        child.parent = path;
      });
    }

    // 确定最终路径
    const resultPath = hideChildrenInMenu
      ? resolveRedirectPath(redirect, path, router)
      : link || path;

    return {
      activeIcon,
      badge,
      badgeType,
      badgeVariants,
      icon,
      name,
      order,
      parent: route.parent,
      parents: route.parents,
      path: resultPath,
      show: !meta.hideInMenu,
      children: resultChildren,
    };
  });

  // 对菜单进行排序，避免order=0时被替换成999的问题
  menus = sortTree(menus, (a, b) => (a?.order ?? 999) - (b?.order ?? 999));

  // 过滤掉隐藏的菜单项
  return filterTree(menus, (menu) => !!menu.show);
}

function resolveRedirectPath(
  redirect: RouteRecordRedirectOption | undefined,
  fallback: string,
  router: Router,
): string {
  if (redirect === undefined || typeof redirect === 'function') {
    return fallback;
  }
  return typeof redirect === 'string'
    ? redirect
    : router.resolve(redirect).path;
}

/**
 * 转换后端菜单数据为路由数据
 * @param menuList 后端菜单数据
 * @param parent 父级菜单
 * @param nameSet 用于跟踪已使用的 name，防止重复
 * @returns 路由数据
 */
function convertServerMenuToRouteRecordStringComponent(
  menuList: AppRouteRecordRaw[],
  parent = '',
  nameSet: Set<string> = new Set(),
): RouteRecordStringComponent[] {
  const menus: RouteRecordStringComponent[] = [];
  menuList.forEach((menu) => {
    // 处理外链菜单（顶级或子级）
    if (isHttpUrl(menu.path)) {
      // 如果有 ?_iframe 参数，则作为内嵌页面处理
      // 如果有 _iframe 参数，则使用 iframeSrc；如果没有，则使用 link
      const url = new URL(menu.path);
      let link: string | undefined;
      let iframeSrc: string | undefined;
      if (url.searchParams.has('_iframe')) {
        url.searchParams.delete('_iframe');
        iframeSrc = url.toString();
      } else {
        link = menu.path;
      }

      const urlMenu: RouteRecordStringComponent = {
        component: 'IFrameView',
        meta: {
          hideInMenu: !menu.visible,
          icon: menu.icon,
          iframeSrc,
          link,
          order: menu.sort,
          title: menu.name,
        },
        name: menu.name,
        path: `${menu.id}`,
      };
      menus.push(urlMenu);
      return;
    }

    let component = menu.component as string | undefined;
    if (menu.children && menu.parentId === 0) {
      component = 'BasicLayout';
    } else if (menu.children && menu.parentId !== 0) {
      component = '';
    } else if (component === 'Layout') {
      component = 'BasicLayout';
    }
    component ??= '';

    const joinedPath = parent ? `${parent}/${menu.path}` : menu.path;
    const path = joinedPath.startsWith('/') ? joinedPath : `/${joinedPath}`;

    // 防止 name 重复，只有在 name 重复时才自动添加 id
    let finalName = menu.componentName || menu.name;
    if (nameSet.has(finalName)) {
      finalName = menu.name + menu.id;
      console.error(`menu name duplicate: ${menu.name}, id: ${menu.id}`, menu);
    }
    nameSet.add(finalName);

    // 处理 menu.component 中的 query 参数
    let query: Record<string, string> | undefined;
    const queryIndex = component.indexOf('?');
    if (queryIndex !== -1) {
      // 提取 query 字符串并解析为对象
      const queryString = component.slice(queryIndex + 1);
      query = Object.fromEntries(new URLSearchParams(queryString).entries());
      // 移除 component 中的 query 部分
      component = component.slice(0, queryIndex);
    }

    const buildMenu: RouteRecordStringComponent = {
      component,
      meta: {
        hideInMenu: !menu.visible,
        icon: menu.icon,
        keepAlive: menu.keepAlive,
        order: menu.sort,
        title: menu.name,
        ...(query && { query }),
      },
      name: finalName,
      path,
    };

    if (menu.children && menu.children.length > 0) {
      buildMenu.children = convertServerMenuToRouteRecordStringComponent(
        menu.children,
        path,
        nameSet,
      );
    }

    menus.push(buildMenu);
  });
  return menus;
}

export { convertServerMenuToRouteRecordStringComponent, generateMenus };
