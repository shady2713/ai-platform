<script lang="ts" setup>
import type { ComponentPublicInstance } from 'vue';

import type {
  CaptchaCheckRequest,
  CaptchaPoint,
  CaptchaSuccessPayload,
} from '@vben/types';

import type { VerificationProps } from './typing';

import {
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
import { resetSize, scaleCaptchaPoints } from './utils/util';

defineOptions({ name: 'VerifyPoints' });

const props = withDefaults(defineProps<VerificationProps>(), {
  barSize: () => ({ height: '40px', width: '310px' }),
  captchaType: 'clickWord',
  imgSize: () => ({ height: '155px', width: '310px' }),
  mode: 'fixed',
  space: 5,
});

const emit = defineEmits<{
  onClose: [];
  onError: [instance: ComponentPublicInstance | null];
  onReady: [instance: ComponentPublicInstance | null];
  onSuccess: [payload: CaptchaSuccessPayload];
}>();

const { barSize, captchaType, checkCaptchaApi, getCaptchaApi, imgSize, mode } =
  toRefs(props);
const instance = getCurrentInstance()?.proxy ?? null;
const rootElement = ref<HTMLElement | null>(null);
const checkNum = ref(3);
const checkPosArr = reactive<CaptchaPoint[]>([]);
const pointBackImgBase = ref('');
const pointTextList = ref<string[]>([]);
const backToken = ref<string>();
const setSize = reactive({
  barHeight: '0px',
  barWidth: '0px',
  imgHeight: '0px',
  imgWidth: '0px',
});
const tempPoints = reactive<CaptchaPoint[]>([]);
const text = ref('');
const barAreaColor = ref('#000');
const barAreaBorderColor = ref('#ddd');
const showRefresh = ref(true);
const bindingClick = ref(true);
const timerIds = new Set<number>();
let challengeVersion = 0;

function schedule(callback: () => void, delay: number) {
  const timerId = window.setTimeout(() => {
    timerIds.delete(timerId);
    callback();
  }, delay);
  timerIds.add(timerId);
}

function clearPoints() {
  tempPoints.splice(0);
  checkPosArr.splice(0);
}

async function init() {
  clearPoints();
  bindingClick.value = true;
  void getPicture();
  await nextTick();
  Object.assign(
    setSize,
    resetSize(rootElement.value, barSize.value, imgSize.value),
  );
  emit('onReady', instance);
}

function preventSelection(event: Event) {
  event.preventDefault();
}

onMounted(() => {
  void init();
  rootElement.value?.addEventListener('selectstart', preventSelection);
});

onBeforeUnmount(() => {
  challengeVersion += 1;
  rootElement.value?.removeEventListener('selectstart', preventSelection);
  for (const timerId of timerIds) window.clearTimeout(timerId);
  timerIds.clear();
});

function getMousePos(event: MouseEvent): CaptchaPoint {
  return { x: event.offsetX, y: event.offsetY };
}

function setFailureState(message?: string) {
  barAreaColor.value = '#d9534f';
  barAreaBorderColor.value = '#d9534f';
  text.value = message || $t('ui.captcha.sliderRotateFailTip');
}

async function verifySelection(
  points: CaptchaPoint[],
  token: string,
  version: number,
) {
  const pointJson = JSON.stringify(points);
  const request: CaptchaCheckRequest = createCaptchaCheckRequest(
    captchaType.value,
    token,
    pointJson,
  );
  try {
    const response = await checkCaptchaApi.value?.(request);
    if (version !== challengeVersion) return;
    const result = parseCaptchaResponse(response);
    if (result?.code !== '0000') {
      emit('onError', instance);
      setFailureState(result?.message);
      schedule(() => void refresh(), 700);
      return;
    }

    barAreaColor.value = '#4cae4c';
    barAreaBorderColor.value = '#5cb85c';
    text.value = $t('ui.captcha.sliderSuccessText');
    bindingClick.value = false;
    const captchaVerification = createCaptchaVerification(token, pointJson);
    if (mode.value === 'pop') {
      schedule(() => {
        emit('onClose');
        void refresh();
      }, 1500);
    }
    emit('onSuccess', { captchaVerification });
  } catch {
    if (version !== challengeVersion) return;
    emit('onError', instance);
    setFailureState();
    schedule(() => void refresh(), 700);
  }
}

function canvasClick(event: MouseEvent) {
  if (!bindingClick.value || checkPosArr.length >= checkNum.value) return;
  const point = getMousePos(event);
  checkPosArr.push(point);
  tempPoints.push({ ...point });
  if (checkPosArr.length < checkNum.value) return;

  const points = scaleCaptchaPoints(checkPosArr, setSize);
  const token = backToken.value;
  if (!points || !token) {
    bindingClick.value = false;
    emit('onError', instance);
    setFailureState();
    schedule(() => void refresh(), 700);
    return;
  }
  bindingClick.value = false;
  const version = challengeVersion;
  schedule(() => void verifySelection(points, token, version), 400);
}

async function refresh() {
  challengeVersion += 1;
  clearPoints();
  barAreaColor.value = '#000';
  barAreaBorderColor.value = '#ddd';
  bindingClick.value = true;
  await getPicture();
  showRefresh.value = true;
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
    if (result?.code !== '0000' || !challenge?.wordList) {
      setFailureState(result?.message);
      return;
    }
    pointBackImgBase.value = `data:image/png;base64,${challenge.originalImageBase64}`;
    backToken.value = challenge.token;
    pointTextList.value = challenge.wordList;
    checkNum.value = challenge.wordList.length;
    text.value = `${$t('ui.captcha.clickInOrder')}【${pointTextList.value.join(',')}】`;
  } catch {
    if (version === challengeVersion) setFailureState();
  }
}

defineExpose({ init, refresh });
</script>

<template>
  <div ref="rootElement" style="position: relative">
    <div class="verify-img-out">
      <div
        :style="{
          width: setSize.imgWidth,
          height: setSize.imgHeight,
          'background-size': `${setSize.imgWidth} ${setSize.imgHeight}`,
          'margin-bottom': `${space}px`,
        }"
        class="verify-img-panel"
      >
        <div
          v-show="showRefresh"
          class="verify-refresh"
          style="z-index: 3"
          @click="refresh"
        >
          <IconifyIcon icon="lucide:refresh-ccw" class="mr-2 size-5" />
        </div>
        <img
          :src="pointBackImgBase"
          alt=""
          style="display: block; width: 100%; height: 100%"
          @click="canvasClick"
        />

        <div
          v-for="(tempPoint, index) in tempPoints"
          :key="index"
          :style="{
            'background-color': '#1abd6c',
            color: '#fff',
            'z-index': 9999,
            width: '20px',
            height: '20px',
            'text-align': 'center',
            'line-height': '20px',
            'border-radius': '50%',
            position: 'absolute',
            top: `${tempPoint.y - 10}px`,
            left: `${tempPoint.x - 10}px`,
          }"
          class="point-area"
        >
          {{ index + 1 }}
        </div>
      </div>
    </div>
    <div
      :style="{
        width: setSize.imgWidth,
        color: barAreaColor,
        'border-color': barAreaBorderColor,
        'line-height': barSize.height,
      }"
      class="verify-bar-area"
    >
      <span class="verify-msg">{{ text }}</span>
    </div>
  </div>
</template>
