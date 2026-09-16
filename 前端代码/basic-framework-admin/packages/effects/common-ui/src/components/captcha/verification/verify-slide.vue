<script lang="ts" setup>
import type { ComponentPublicInstance } from 'vue';

import type { CaptchaCheckRequest, CaptchaSuccessPayload } from '@vben/types';

import type { VerificationProps } from './typing';

/**
 * VerifySlide
 * @description 滑块
 */
import {
  computed,
  getCurrentInstance,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  toRefs,
} from 'vue';

import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';

import {
  createCaptchaCheckRequest,
  createCaptchaVerification,
  parseCaptchaChallengeResponse,
  parseCaptchaResponse,
} from './contract';
import { resetSize } from './utils/util';

const props = withDefaults(defineProps<VerificationProps>(), {
  barSize: () => ({
    height: '40px',
    width: '310px',
  }),
  blockSize: () => ({
    height: '50px',
    width: '50px',
  }),
  captchaType: 'blockPuzzle',
  explain: '',
  imgSize: () => ({
    height: '155px',
    width: '310px',
  }),
  mode: 'fixed',
  type: '1',
  space: 5,
});

const emit = defineEmits<{
  onClose: [];
  onError: [instance: ComponentPublicInstance | null];
  onReady: [instance: ComponentPublicInstance | null];
  onSuccess: [payload: CaptchaSuccessPayload];
}>();

const {
  blockSize,
  captchaType,
  explain,
  mode,
  checkCaptchaApi,
  getCaptchaApi,
} = toRefs(props);

const instance = getCurrentInstance()?.proxy ?? null;
const rootElement = ref<HTMLElement | null>(null);
const passFlag = ref(false); // 是否通过的标识
const backImgBase = ref(''); // 验证码背景图片
const blockBackImgBase = ref(''); // 验证滑块的背景图片
const backToken = ref<string>(); // 后端返回的唯一token值
const startMoveTime = ref(0); // 移动开始的时间
const endMoveTime = ref(0); // 移动结束的时间
const tipWords = ref('');
const text = ref('');
const finishText = ref('');
const setSize = reactive({
  barHeight: '0px',
  barWidth: '0px',
  imgHeight: '0px',
  imgWidth: '0px',
});
const moveBlockLeft = ref('0px');
const leftBarWidth = ref<string>();
// 移动中样式
const moveBlockBackgroundColor = ref('#fff');
const leftBarBorderColor = ref('#ddd');
const iconColor = ref('#000');
const iconClass = ref('icon-right');
const status = ref(false); // 鼠标状态
const isEnd = ref(false); // 是够验证完成
const showRefresh = ref(true);
const transitionLeft = ref('');
const transitionWidth = ref('');
const startLeft = ref(0);
const timerIds = new Set<number>();
let challengeVersion = 0;

const barArea = computed(() => {
  return rootElement.value?.querySelector<HTMLElement>('.verify-bar-area');
});

function schedule(callback: () => void, delay: number) {
  const timerId = window.setTimeout(() => {
    timerIds.delete(timerId);
    callback();
  }, delay);
  timerIds.add(timerId);
}

function init() {
  text.value =
    explain.value === '' ? $t('ui.captcha.sliderDefaultText') : explain.value;

  void getPicture();
  nextTick(() => {
    Object.assign(
      setSize,
      resetSize(rootElement.value, props.barSize, props.imgSize),
    );
    emit('onReady', instance);
  });

  window.removeEventListener('touchmove', move);
  window.removeEventListener('mousemove', move);

  // 鼠标松开
  window.removeEventListener('touchend', end);
  window.removeEventListener('mouseup', end);

  window.addEventListener('touchmove', move);
  window.addEventListener('mousemove', move);

  // 鼠标松开
  window.addEventListener('touchend', end);
  window.addEventListener('mouseup', end);
}

onMounted(() => {
  // 禁止拖拽
  init();
  rootElement.value?.addEventListener('selectstart', preventSelection);
});

function preventSelection(event: Event) {
  event.preventDefault();
}

onBeforeUnmount(() => {
  challengeVersion += 1;
  rootElement.value?.removeEventListener('selectstart', preventSelection);
  window.removeEventListener('touchmove', move);
  window.removeEventListener('mousemove', move);
  window.removeEventListener('touchend', end);
  window.removeEventListener('mouseup', end);
  for (const timerId of timerIds) window.clearTimeout(timerId);
  timerIds.clear();
});

// 鼠标按下
function start(e: MouseEvent | TouchEvent) {
  const area = barArea.value;
  if (!area) return;
  const x =
    ((e as TouchEvent).touches
      ? (e as TouchEvent).touches[0]?.pageX
      : (e as MouseEvent).clientX) || 0;
  startLeft.value = Math.floor(x - area.getBoundingClientRect().left);
  startMoveTime.value = Date.now(); // 开始滑动的时间
  if (isEnd.value === false) {
    text.value = '';
    moveBlockBackgroundColor.value = '#337ab7';
    leftBarBorderColor.value = '#337AB7';
    iconColor.value = '#fff';
    e.stopPropagation();
    status.value = true;
  }
}
// 鼠标移动
function move(e: MouseEvent | TouchEvent) {
  if (status.value && isEnd.value === false) {
    const area = barArea.value;
    if (!area) return;
    const x =
      ((e as TouchEvent).touches
        ? (e as TouchEvent).touches[0]?.pageX
        : (e as MouseEvent).clientX) || 0;
    const barAreaLeft = area.getBoundingClientRect().left;
    let moveBlockPosition = x - barAreaLeft;
    if (
      moveBlockPosition >=
      area.offsetWidth - Number.parseInt(blockSize.value.width) / 2 - 2
    )
      moveBlockPosition =
        area.offsetWidth - Number.parseInt(blockSize.value.width) / 2 - 2;

    if (moveBlockPosition <= 0)
      moveBlockPosition = Number.parseInt(blockSize.value.width) / 2;

    moveBlockLeft.value = `${moveBlockPosition - startLeft.value}px`;
    leftBarWidth.value = `${moveBlockPosition - startLeft.value}px`;
  }
}

function showVerificationFailure(message?: string) {
  moveBlockBackgroundColor.value = '#d9534f';
  leftBarBorderColor.value = '#d9534f';
  iconColor.value = '#fff';
  iconClass.value = 'icon-close';
  passFlag.value = false;
  tipWords.value = message || $t('ui.captcha.sliderRotateFailTip');
  emit('onError', instance);
  schedule(() => void refresh(), 1000);
  schedule(() => {
    tipWords.value = '';
  }, 1000);
}

async function end() {
  endMoveTime.value = Date.now();
  if (!status.value || isEnd.value) return;
  status.value = false;
  const imageWidth = Number.parseFloat(setSize.imgWidth);
  const token = backToken.value;
  const rawDistance = Number.parseFloat(moveBlockLeft.value);
  if (
    !token ||
    !Number.isFinite(imageWidth) ||
    imageWidth <= 0 ||
    !Number.isFinite(rawDistance)
  ) {
    showVerificationFailure();
    return;
  }

  const moveLeftDistance = (rawDistance * 310) / imageWidth;
  const pointJson = JSON.stringify({ x: moveLeftDistance, y: 5 });
  const request: CaptchaCheckRequest = createCaptchaCheckRequest(
    captchaType.value,
    token,
    pointJson,
  );
  const version = challengeVersion;
  try {
    const response = await checkCaptchaApi.value?.(request);
    if (version !== challengeVersion) return;
    const result = parseCaptchaResponse(response);
    if (result?.code !== '0000') {
      showVerificationFailure(result?.message);
      return;
    }

    moveBlockBackgroundColor.value = '#5cb85c';
    leftBarBorderColor.value = '#5cb85c';
    iconColor.value = '#fff';
    iconClass.value = 'icon-check';
    showRefresh.value = false;
    isEnd.value = true;
    passFlag.value = true;
    tipWords.value = `${((endMoveTime.value - startMoveTime.value) / 1000).toFixed(2)}s ${$t('ui.captcha.title')}`;
    const captchaVerification = createCaptchaVerification(token, pointJson);
    schedule(() => {
      tipWords.value = '';
      emit('onSuccess', { captchaVerification });
      if (mode.value === 'pop') {
        emit('onClose');
        void refresh();
      }
    }, 1000);
  } catch {
    if (version === challengeVersion) showVerificationFailure();
  }
}

async function refresh() {
  challengeVersion += 1;
  showRefresh.value = true;
  finishText.value = '';

  transitionLeft.value = 'left .3s';
  moveBlockLeft.value = '0px';

  leftBarWidth.value = undefined;
  transitionWidth.value = 'width .3s';

  leftBarBorderColor.value = '#ddd';
  moveBlockBackgroundColor.value = '#fff';
  iconColor.value = '#000';
  iconClass.value = 'icon-right';
  isEnd.value = false;

  await getPicture();
  schedule(() => {
    transitionWidth.value = '';
    transitionLeft.value = '';
    text.value =
      explain.value === '' ? $t('ui.captcha.sliderDefaultText') : explain.value;
  }, 300);
}

async function getPicture() {
  const version = ++challengeVersion;
  try {
    const response = await getCaptchaApi.value?.({
      captchaType: captchaType.value,
    });
    if (version !== challengeVersion) return;
    const result = parseCaptchaChallengeResponse(response, captchaType.value);
    const challenge = result?.data;
    if (result?.code !== '0000' || !challenge?.jigsawImageBase64) {
      tipWords.value = result?.message || $t('ui.captcha.sliderRotateFailTip');
      emit('onError', instance);
      return;
    }
    backImgBase.value = `data:image/png;base64,${challenge.originalImageBase64}`;
    blockBackImgBase.value = `data:image/png;base64,${challenge.jigsawImageBase64}`;
    backToken.value = challenge.token;
  } catch {
    if (version === challengeVersion) {
      tipWords.value = $t('ui.captcha.sliderRotateFailTip');
      emit('onError', instance);
    }
  }
}
defineExpose({
  init,
  refresh,
});
</script>

<template>
  <div ref="rootElement" style="position: relative">
    <div
      v-if="type === '2'"
      :style="{ height: `${Number.parseInt(setSize.imgHeight) + space}px` }"
      class="verify-img-out"
    >
      <div
        :style="{ width: setSize.imgWidth, height: setSize.imgHeight }"
        class="verify-img-panel"
      >
        <img
          :src="backImgBase"
          alt=""
          style="display: block; width: 100%; height: 100%"
        />
        <div v-show="showRefresh" class="verify-refresh" @click="refresh">
          <IconifyIcon icon="lucide:refresh-ccw" class="mr-2 size-5" />
        </div>
        <transition name="tips">
          <span
            v-if="tipWords"
            :class="passFlag ? 'suc-bg' : 'err-bg'"
            class="verify-tips"
          >
            {{ tipWords }}
          </span>
        </transition>
      </div>
    </div>
    <!-- 公共部分 -->
    <div
      :style="{
        width: setSize.imgWidth,
        height: barSize.height,
        'line-height': barSize.height,
      }"
      class="verify-bar-area"
    >
      <span class="verify-msg" v-text="text"></span>
      <div
        :style="{
          width: leftBarWidth !== undefined ? leftBarWidth : barSize.height,
          height: barSize.height,
          'border-color': leftBarBorderColor,
          transition: transitionWidth,
        }"
        class="verify-left-bar"
      >
        <span class="verify-msg" v-text="finishText"></span>
        <div
          :style="{
            width: barSize.height,
            height: barSize.height,
            'background-color': moveBlockBackgroundColor,
            left: moveBlockLeft,
            transition: transitionLeft,
          }"
          class="verify-move-block"
          @mousedown="start"
          @touchstart="start"
        >
          <i
            :class="[iconClass]"
            :style="{ color: iconColor }"
            class="iconfont verify-icon"
          ></i>
          <div
            v-if="type === '2'"
            :style="{
              width: `${Math.floor((Number.parseInt(setSize.imgWidth) * 47) / 310)}px`,
              height: setSize.imgHeight,
              top: `-${Number.parseInt(setSize.imgHeight) + space}px`,
              'background-size': `${setSize.imgWidth} ${setSize.imgHeight}`,
            }"
            class="verify-sub-block"
          >
            <img
              :src="blockBackImgBase"
              alt=""
              style="
                display: block;
                width: 100%;
                height: 100%;
                -webkit-user-drag: none;
              "
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
