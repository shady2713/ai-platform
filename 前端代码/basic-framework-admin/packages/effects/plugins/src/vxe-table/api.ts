import type { VxeGridInstance } from 'vxe-table';

import type {
  BaseFormComponentType,
  ExtendedFormApi,
} from '@vben-core/form-ui';

import type { VxeGridProps, VxeGridRow } from './types';

import { toRaw } from 'vue';

import { Store } from '@vben-core/shared/store';
import {
  bindMethods,
  isBoolean,
  isFunction,
  mergeWithArrayOverride,
} from '@vben-core/shared/utils';

function getDefaultState<
  T extends object,
  D extends BaseFormComponentType,
>(): VxeGridProps<T, D> {
  return {
    class: '',
    gridClass: '',
    gridOptions: {},
    gridEvents: {},
    formOptions: undefined,
    showSearchForm: true,
  };
}

export class VxeGridApi<
  T extends object = VxeGridRow,
  D extends BaseFormComponentType = BaseFormComponentType,
> {
  public state: null | VxeGridProps<T, D> = null;
  public store: Store<VxeGridProps<T, D>>;

  get formApi(): ExtendedFormApi {
    if (!this.formApiInstance) {
      throw new Error('VxeGridApi is not mounted: form API is unavailable');
    }
    return this.formApiInstance;
  }
  get grid(): VxeGridInstance<T> {
    if (!this.gridInstance) {
      throw new Error('VxeGridApi is not mounted: grid is unavailable');
    }
    return this.gridInstance;
  }

  private formApiInstance: ExtendedFormApi | null = null;

  private gridInstance: null | VxeGridInstance<T> = null;

  constructor(options: VxeGridProps<T, D> = {}) {
    this.store = new Store<VxeGridProps<T, D>>(
      mergeWithArrayOverride(options, getDefaultState<T, D>()),
      {
        onUpdate: () => {
          this.state = this.store.state;
        },
      },
    );
    this.state = this.store.state;
    bindMethods(this);
  }

  mount(instance: null | VxeGridInstance<T>, formApi: ExtendedFormApi): void {
    if (!this.gridInstance && instance) {
      this.gridInstance = instance;
      this.formApiInstance = formApi;
    }
  }

  async query(params: Record<string, unknown> = {}): Promise<void> {
    await this.grid.commitProxy('query', toRaw(params));
  }

  async reload(params: Record<string, unknown> = {}): Promise<void> {
    await this.grid.commitProxy('reload', toRaw(params));
  }

  setGridOptions(options: Partial<VxeGridProps<T, D>['gridOptions']>): void {
    this.setState({ gridOptions: options });
  }

  setLoading(isLoading: boolean): void {
    this.setState({ gridOptions: { loading: isLoading } });
  }

  setState(
    stateOrFn:
      | ((prev: VxeGridProps<T, D>) => Partial<VxeGridProps<T, D>>)
      | Partial<VxeGridProps<T, D>>,
  ): void {
    this.store.setState((prev) =>
      mergeWithArrayOverride(
        isFunction(stateOrFn) ? stateOrFn(prev) : stateOrFn,
        prev,
      ),
    );
  }

  toggleSearchForm(show?: boolean): boolean | undefined {
    this.setState({
      showSearchForm: isBoolean(show) ? show : !this.state?.showSearchForm,
    });
    return this.state?.showSearchForm;
  }

  unmount(): void {
    this.gridInstance = null;
    this.formApiInstance = null;
  }
}
