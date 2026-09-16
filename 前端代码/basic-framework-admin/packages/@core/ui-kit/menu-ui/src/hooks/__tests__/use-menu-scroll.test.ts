import { nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useMenuScroll } from '../use-menu-scroll';

describe('useMenuScroll', () => {
  let scrollIntoView: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  function addActiveItem() {
    const aside = document.createElement('aside');
    aside.innerHTML = '<li role="menuitem" class="is-active"></li>';
    document.body.append(aside);
    return aside.querySelector('li') as HTMLLIElement;
  }

  it('scrolls the active item into view when enabled', () => {
    const activeItem = addActiveItem();
    const activePath = ref('/a');
    const { scrollToActiveItem } = useMenuScroll(activePath, { enable: true });
    scrollToActiveItem();
    expect(activeItem.scrollIntoView).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'center',
      inline: 'center',
    });
  });

  it('does nothing when the boolean enable flag is false', () => {
    addActiveItem();
    const activePath = ref('/a');
    const { scrollToActiveItem } = useMenuScroll(activePath, { enable: false });
    scrollToActiveItem();
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('reads the enable flag from a ref', () => {
    const activeItem = addActiveItem();
    const activePath = ref('/a');
    const enable = ref(false);
    const { scrollToActiveItem } = useMenuScroll(activePath, { enable });
    scrollToActiveItem();
    expect(scrollIntoView).not.toHaveBeenCalled();

    enable.value = true;
    scrollToActiveItem();
    expect(activeItem.scrollIntoView).toHaveBeenCalled();
  });

  it('debounces the scroll when the active path changes', async () => {
    vi.useFakeTimers();
    const activeItem = addActiveItem();
    const activePath = ref('/a');
    useMenuScroll(activePath, { delay: 100 });
    activePath.value = '/b';
    expect(scrollIntoView).not.toHaveBeenCalled();
    await nextTick();
    vi.advanceTimersByTime(100);
    expect(activeItem.scrollIntoView).toHaveBeenCalled();
  });

  it('does not watch the path when the enable ref is false', async () => {
    vi.useFakeTimers();
    addActiveItem();
    const activePath = ref('/a');
    useMenuScroll(activePath, { delay: 100, enable: ref(false) });
    activePath.value = '/b';
    await nextTick();
    vi.advanceTimersByTime(200);
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('uses the default delay of 320ms', async () => {
    vi.useFakeTimers();
    addActiveItem();
    const activePath = ref('/a');
    useMenuScroll(activePath);
    activePath.value = '/b';
    await nextTick();
    vi.advanceTimersByTime(319);
    expect(scrollIntoView).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(scrollIntoView).toHaveBeenCalled();
  });
});
