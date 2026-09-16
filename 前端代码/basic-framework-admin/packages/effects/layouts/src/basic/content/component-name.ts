import type { VNode } from 'vue';

interface NamedComponentType {
  name?: string;
}

function isNamedComponentType(
  componentType: VNode['type'],
): componentType is NamedComponentType & VNode['type'] {
  return typeof componentType === 'object' && componentType !== null;
}

/** Adds a route name only to mutable object component definitions. */
export function setVNodeComponentName(component: VNode, routeName: string) {
  if (!isNamedComponentType(component.type) || component.type.name) {
    return component;
  }

  component.type.name = routeName;
  return component;
}
