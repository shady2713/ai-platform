import type { App, Directive, DirectiveBinding } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { registerAccessDirective } from './directive';

type AccessRequirement = string | string[] | undefined;

const access = vi.hoisted(() => ({
  accessMode: { value: 'frontend' },
  hasAccessByCodes: vi.fn(),
  hasAccessByRoles: vi.fn(),
}));

vi.mock('./use-access', () => ({
  useAccess: () => access,
}));

function getMountedHook() {
  const app = { directive: vi.fn() };
  registerAccessDirective(app as unknown as App);
  expect(app.directive).toHaveBeenCalledWith('access', expect.any(Object));
  const directive = app.directive.mock.calls[0]?.[1] as Directive<
    Element,
    AccessRequirement
  >;
  if (typeof directive === 'function' || !directive.mounted) {
    throw new TypeError('access directive must provide an object mounted hook');
  }
  return directive.mounted as (
    element: Element,
    binding: DirectiveBinding<AccessRequirement>,
  ) => void;
}

function createBinding(
  value: AccessRequirement,
  arg?: string,
): DirectiveBinding<AccessRequirement> {
  return { arg, value } as DirectiveBinding<AccessRequirement>;
}

describe('access directive', () => {
  beforeEach(() => {
    access.accessMode.value = 'frontend';
    access.hasAccessByCodes.mockReset();
    access.hasAccessByRoles.mockReset();
  });

  it('keeps the element when no access requirement is configured', () => {
    const mounted = getMountedHook();
    const element = { remove: vi.fn() } as unknown as Element;

    mounted(element, createBinding(undefined));

    expect(access.hasAccessByCodes).not.toHaveBeenCalled();
    expect(access.hasAccessByRoles).not.toHaveBeenCalled();
    expect(element.remove).not.toHaveBeenCalled();
  });

  it('uses role checks for frontend role bindings', () => {
    const mounted = getMountedHook();
    const element = { remove: vi.fn() } as unknown as Element;
    access.hasAccessByRoles.mockReturnValue(true);

    mounted(element, createBinding('admin', 'role'));

    expect(access.hasAccessByRoles).toHaveBeenCalledWith(['admin']);
    expect(access.hasAccessByCodes).not.toHaveBeenCalled();
    expect(element.remove).not.toHaveBeenCalled();
  });

  it('removes an element when its code requirement is denied', () => {
    const mounted = getMountedHook();
    const element = { remove: vi.fn() } as unknown as Element;
    access.hasAccessByCodes.mockReturnValue(false);

    mounted(element, createBinding(['read', 'write'], 'code'));

    expect(access.hasAccessByCodes).toHaveBeenCalledWith(['read', 'write']);
    expect(element.remove).toHaveBeenCalledOnce();
  });

  it('uses code checks for role bindings in backend mode', () => {
    const mounted = getMountedHook();
    const element = { remove: vi.fn() } as unknown as Element;
    access.accessMode.value = 'backend';
    access.hasAccessByCodes.mockReturnValue(true);

    mounted(element, createBinding('admin', 'role'));

    expect(access.hasAccessByCodes).toHaveBeenCalledWith(['admin']);
    expect(access.hasAccessByRoles).not.toHaveBeenCalled();
    expect(element.remove).not.toHaveBeenCalled();
  });
});
