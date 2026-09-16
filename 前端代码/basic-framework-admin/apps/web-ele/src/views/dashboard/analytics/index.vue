<script lang="ts" setup>
/** 仪表盘欢迎页面 */
import { computed } from 'vue';
import { useRouter } from 'vue-router';

import { useAccessStore, useUserStore } from '@vben/stores';

import { ElCard, ElCol, ElRow, ElTag } from 'element-plus';

defineOptions({ name: 'DashboardWelcome' });

const router = useRouter();
const userStore = useUserStore();
const accessStore = useAccessStore();

const userInfo = computed(() => userStore.userInfo);
const userRoles = computed(() => userStore.userRoles);

const quickLinks = [
  { title: '用户管理', path: '/system/user', description: '管理系统用户账号' },
  { title: '角色管理', path: '/system/role', description: '配置角色与权限' },
  {
    title: '部门管理',
    path: '/system/dept',
    description: '组织架构与部门管理',
  },
  { title: '字典管理', path: '/system/dict', description: '维护系统字典数据' },
].filter((link) => accessStore.getMenuByPath(link.path));

function navigateTo(path: string) {
  router.push(path);
}

function getGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 9) return '早上好';
  if (hour < 12) return '上午好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}
</script>

<template>
  <div class="p-6">
    <!-- Welcome Section -->
    <ElCard shadow="never" class="welcome-card">
      <div class="welcome-content">
        <div class="welcome-text">
          <h1 class="welcome-title">
            {{ getGreeting() }}，{{
              userInfo?.nickname || userInfo?.username || '用户'
            }}
          </h1>
          <p class="welcome-desc">欢迎回到管理系统后台</p>
        </div>
        <div v-if="userInfo?.avatar" class="welcome-avatar">
          <img :src="userInfo.avatar" alt="avatar" class="avatar-img" />
        </div>
      </div>
    </ElCard>

    <ElRow :gutter="16" class="mt-4">
      <!-- User Info Card -->
      <ElCol :span="8">
        <ElCard shadow="never">
          <template #header>
            <span class="card-header-title">当前用户</span>
          </template>
          <div class="info-list">
            <div class="info-item">
              <span class="info-label">用户名</span>
              <span class="info-value">{{ userInfo?.username || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">昵称</span>
              <span class="info-value">{{ userInfo?.nickname || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">邮箱</span>
              <span class="info-value">{{ userInfo?.email || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">角色</span>
              <span class="info-value">
                <template v-if="userRoles.length > 0">
                  <ElTag
                    v-for="role in userRoles"
                    :key="role"
                    size="small"
                    class="mr-1"
                  >
                    {{ role }}
                  </ElTag>
                </template>
                <span v-else>-</span>
              </span>
            </div>
          </div>
        </ElCard>
      </ElCol>

      <!-- Project Info Card -->
      <ElCol :span="8">
        <ElCard shadow="never">
          <template #header>
            <span class="card-header-title">项目信息</span>
          </template>
          <div class="info-list">
            <div class="info-item">
              <span class="info-label">项目名称</span>
              <span class="info-value">Basic Framework Admin</span>
            </div>
            <div class="info-item">
              <span class="info-label">框架版本</span>
              <span class="info-value">5.6.0</span>
            </div>
            <div class="info-item">
              <span class="info-label">前端框架</span>
              <span class="info-value">Vue 3 + TypeScript</span>
            </div>
            <div class="info-item">
              <span class="info-label">UI 组件库</span>
              <span class="info-value">Element Plus</span>
            </div>
            <div class="info-item">
              <span class="info-label">构建工具</span>
              <span class="info-value">Vite</span>
            </div>
          </div>
        </ElCard>
      </ElCol>

      <!-- Quick Links Card -->
      <ElCol :span="8">
        <ElCard shadow="never">
          <template #header>
            <span class="card-header-title">快捷入口</span>
          </template>
          <div class="quick-links">
            <div
              v-for="link in quickLinks"
              :key="link.path"
              class="quick-link-item"
              @click="navigateTo(link.path)"
            >
              <div class="quick-link-title">{{ link.title }}</div>
              <div class="quick-link-desc">{{ link.description }}</div>
            </div>
          </div>
        </ElCard>
      </ElCol>
    </ElRow>
  </div>
</template>

<style scoped>
.welcome-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.welcome-card :deep(.el-card__body) {
  padding: 24px 32px;
}

.welcome-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.welcome-title {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 600;
  color: #fff;
}

.welcome-desc {
  margin: 0;
  font-size: 14px;
  color: rgb(255 255 255 / 85%);
}

.welcome-avatar {
  flex-shrink: 0;
}

.avatar-img {
  width: 64px;
  height: 64px;
  border: 3px solid rgb(255 255 255 / 50%);
  border-radius: 50%;
}

.card-header-title {
  font-size: 15px;
  font-weight: 600;
}

.info-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
}

.info-label {
  flex-shrink: 0;
  color: #909399;
}

.info-value {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  color: #303133;
  text-align: right;
}

.quick-links {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.quick-link-item {
  padding: 10px 12px;
  cursor: pointer;
  border-radius: 6px;
  transition: background-color 0.2s;
}

.quick-link-item:hover {
  background-color: #f5f7fa;
}

.quick-link-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.quick-link-desc {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}
</style>
