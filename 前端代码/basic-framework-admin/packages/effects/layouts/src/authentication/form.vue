<script setup lang="ts">
defineOptions({
  name: 'AuthenticationFormView',
});

defineProps<{
  dataSide: 'bottom' | 'left' | 'right' | 'top';
}>();
</script>

<template>
  <div
    class="flex-col-center dark:bg-background-deep bg-background relative px-6 py-10 lg:flex-initial lg:px-8"
  >
    <slot></slot>
    <RouterView v-slot="{ Component, route }">
      <Transition appear mode="out-in" name="slide-right">
        <KeepAlive :include="['Login']">
          <component
            :is="Component"
            :key="route.fullPath"
            class="side-content mt-6 w-full sm:mx-auto md:max-w-md"
            :data-side="dataSide"
          />
        </KeepAlive>
      </Transition>
    </RouterView>

    <footer
      v-if="$slots.copyright"
      class="text-muted-foreground absolute bottom-3 flex text-center text-xs"
    >
      <slot name="copyright"></slot>
    </footer>
  </div>
</template>
