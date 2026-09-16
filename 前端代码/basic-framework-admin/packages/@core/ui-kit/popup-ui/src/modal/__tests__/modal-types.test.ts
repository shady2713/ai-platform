import type { ModalApiOptions, ModalProps, ModalState } from '../modal';

import { describe, expect, it } from 'vitest';

// Side-effect import executes the type-only modal contract module.
import '../modal';

describe('modal contract types', () => {
  it('modalState extends ModalProps with the open flag', () => {
    const props: ModalProps = {
      animationType: 'scale',
      closeOnClickModal: false,
      title: 'Edit',
    };
    const state: ModalState = { ...props, isOpen: true };
    expect(state.isOpen).toBe(true);
    expect(state.animationType).toBe('scale');
    expect(state.closeOnClickModal).toBe(false);
    expect(state.title).toBe('Edit');
  });

  it('modalApiOptions routes lifecycle callbacks', () => {
    const calls: string[] = [];
    const options: ModalApiOptions = {
      onBeforeClose: () => true,
      onCancel: () => calls.push('cancel'),
      onConfirm: () => calls.push('confirm'),
      onOpenChange: (isOpen) => calls.push(`open:${isOpen}`),
    };
    options.onCancel?.();
    options.onConfirm?.();
    options.onOpenChange?.(false);
    expect(calls).toEqual(['cancel', 'confirm', 'open:false']);
    expect(options.onBeforeClose?.()).toBe(true);
  });
});
