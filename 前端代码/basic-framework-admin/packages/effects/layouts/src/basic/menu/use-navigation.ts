import { useRouter } from 'vue-router';

import { openRouteInNewWindow, openWindow } from '@vben/utils';

import { resolveNavigationDestination } from '../../navigation-destination';

function useNavigation() {
  const router = useRouter();
  const findRoute = (path: string) =>
    router.getRoutes().find((route) => route.path === path);

  const shouldOpenInNewWindow = (path: string): boolean => {
    if (resolveNavigationDestination(path)?.kind === 'external') {
      return true;
    }
    const route = findRoute(path);
    return !!(route?.meta?.link || route?.meta?.openInNewWindow);
  };

  const resolveHref = (path: string): string => {
    return router.resolve(path).href;
  };

  const navigation = async (path: string) => {
    const route = findRoute(path);
    const { openInNewWindow = false, query = {}, link } = route?.meta ?? {};
    const destination = resolveNavigationDestination(
      typeof link === 'string' ? link : path,
    );
    if (!destination) {
      throw new TypeError('Unsupported navigation destination.');
    }
    if (destination.kind === 'external') {
      openWindow(destination.url, { target: '_blank' });
    } else if (openInNewWindow) {
      openRouteInNewWindow(resolveHref(destination.path));
    } else {
      await router.push({
        path: destination.path,
        query,
      });
    }
  };

  const willOpenedByWindow = (path: string) => {
    return shouldOpenInNewWindow(path);
  };

  return { navigation, willOpenedByWindow };
}

export { useNavigation };
