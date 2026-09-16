<script setup lang="ts">
defineOptions({
  name: 'DocLink',
});
</script>

<template>
  <div class="w-full sm:mx-auto md:max-w-md">
    <div class="mt-4 flex items-center justify-between">
      <span class="border-input w-[35%] border-b dark:border-gray-600"></span>
      <span class="text-muted-foreground text-center text-xs uppercase">
        使用提示
      </span>
      <span class="border-input w-[35%] border-b dark:border-gray-600"></span>
    </div>

    <div class="text-muted-foreground mt-4 space-y-2 text-sm">
      <p>1. 首次部署后，请先确认接口地址、账号权限和环境变量配置。</p>
      <p>2. 如需项目说明或交付文档，请联系当前项目维护人获取内部资料。</p>
      <p>3. 对外发布前，建议再次检查品牌文案、演示地址和第三方资源引用。</p>
    </div>
  </div>
</template>
