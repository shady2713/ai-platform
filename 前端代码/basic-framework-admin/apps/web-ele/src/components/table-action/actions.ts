import type { ButtonProps } from 'element-plus';

import type { ActionItem, ButtonType, PopConfirm } from './typing';

export type AccessChecker = (codes: string[]) => boolean;

export type ResolvedAction = ActionItem & {
  enable: boolean;
  label: string;
  onCancel?: () => void;
  onConfirm?: () => void;
  type: ButtonType;
};

export type DropdownAction = Omit<ActionItem, 'text'> & {
  divider: boolean;
  label: string;
  onCancel?: () => void;
  onConfirm?: () => void;
  text: string;
};

export function isActionVisible(
  action: ActionItem,
  hasAccessByCodes: AccessChecker,
): boolean {
  const auth = action.auth ?? [];
  if (auth.length > 0 && !hasAccessByCodes(auth)) {
    return false;
  }

  if (typeof action.ifShow === 'function') {
    return action.ifShow(action);
  }
  return action.ifShow !== false;
}

export function resolveActions(
  actions: ActionItem[],
  hasAccessByCodes: AccessChecker,
): ResolvedAction[] {
  return actions
    .filter((action) => isActionVisible(action, hasAccessByCodes))
    .map((action) => ({
      ...action,
      enable: Boolean(action.popConfirm),
      label: action.label ?? '',
      onCancel: action.popConfirm?.cancel,
      onConfirm: action.popConfirm?.confirm,
      type: action.type ?? 'primary',
    }));
}

export function resolveDropdownActions(
  actions: ActionItem[],
  divider: boolean,
  hasAccessByCodes: AccessChecker,
): DropdownAction[] {
  const visibleActions = actions.filter((action) =>
    isActionVisible(action, hasAccessByCodes),
  );

  return visibleActions.map((action, index) => ({
    ...action,
    divider: divider && index < visibleActions.length - 1,
    label: action.label ?? '',
    onCancel: action.popConfirm?.cancel,
    onConfirm: action.popConfirm?.confirm,
    text: action.label ?? '',
  }));
}

export function getPopConfirmProps(
  popConfirm?: PopConfirm,
): Record<string, unknown> {
  if (!popConfirm) {
    return {};
  }

  const {
    cancel,
    cancelText,
    confirm,
    icon: _icon,
    okText,
    ...props
  } = popConfirm;
  return {
    ...props,
    ...(cancelText ? { cancelButtonText: cancelText } : {}),
    ...(okText ? { confirmButtonText: okText } : {}),
    ...(cancel ? { onCancel: cancel } : {}),
    onConfirm: confirm,
  };
}

export function getButtonProps(
  action: ResolvedAction,
): Partial<ButtonProps> & { type: ButtonType } {
  const {
    auth: _auth,
    divider: _divider,
    enable: _enable,
    icon: _icon,
    ifShow: _ifShow,
    label: _label,
    onCancel: _onCancel,
    onClick: _onClick,
    onConfirm: _onConfirm,
    popConfirm: _popConfirm,
    tooltip: _tooltip,
    ...buttonProps
  } = action;
  return {
    ...buttonProps,
    type: action.type ?? 'primary',
  };
}

export function getTooltipProps(
  tooltip: ActionItem['tooltip'],
): Record<string, unknown> | undefined {
  if (typeof tooltip === 'string') {
    return tooltip ? { content: tooltip } : undefined;
  }
  return tooltip?.content ? { ...tooltip } : undefined;
}

export function invokeDropdownAction(action?: DropdownAction): void {
  if (
    !action ||
    action.disabled ||
    action.popConfirm ||
    typeof action.onClick !== 'function'
  ) {
    return;
  }
  action.onClick();
}
