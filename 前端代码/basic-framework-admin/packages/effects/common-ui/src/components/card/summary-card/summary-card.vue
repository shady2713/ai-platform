<script lang="ts" setup>
import type { SummaryCardProps } from './types';

import { ChevronDown, ChevronUp, CircleAlert } from '@vben/icons';

import {
  Card,
  CardContent,
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
  VbenCountToAnimator,
  VbenIcon,
} from '@vben-core/shadcn-ui';

/** 统计卡片 */
defineOptions({ name: 'SummaryCard' });

defineProps<SummaryCardProps>();
</script>

<template>
  <Card class="h-full">
    <CardContent class="flex items-center gap-3 p-6">
      <div
        v-if="icon"
        class="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded"
        :class="`${iconColor} ${iconBgColor}`"
      >
        <VbenIcon :icon="icon" class="size-6" />
      </div>
      <div class="flex flex-col gap-1">
        <div class="flex items-center gap-1">
          <span class="text-muted-foreground text-sm">{{ title }}</span>
          <TooltipProvider v-if="tooltip">
            <Tooltip>
              <TooltipTrigger>
                <CircleAlert class="text-muted-foreground size-3" />
              </TooltipTrigger>
              <TooltipContent>
                <p>{{ tooltip }}</p>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </div>
        <div class="flex flex-row items-baseline gap-2">
          <div class="text-2xl">
            <VbenCountToAnimator
              :prefix="prefix"
              :end-val="value ?? 0"
              :decimals="decimals ?? 0"
            />
          </div>
          <span
            v-if="percent !== undefined"
            :class="
              Number(percent) > 0
                ? 'text-destructive'
                : 'text-emerald-600 dark:text-emerald-400'
            "
            class="flex items-center"
          >
            <span class="text-sm">{{ Math.abs(Number(percent)) }}%</span>
            <ChevronUp v-if="Number(percent) > 0" class="ml-0.5 size-3" />
            <ChevronDown v-else class="ml-0.5 size-3" />
          </span>
        </div>
      </div>
    </CardContent>
  </Card>
</template>
