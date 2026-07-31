<script setup>
import {Calendar, ChatDotSquare, Files, Notification, School} from '@element-plus/icons-vue'

const campusServices = [
  {
    title: '帖子广场',
    description: '发现校园里的新鲜讨论',
    icon: ChatDotSquare,
    tone: 'blue'
  },
  {
    title: '校园活动',
    description: '不错过每一次精彩相遇',
    icon: Calendar,
    tone: 'orange'
  },
  {
    title: '教务服务',
    description: '课程、通知与学习资源',
    icon: Files,
    tone: 'green'
  },
  {
    title: '消息中心',
    description: '及时收到重要动态提醒',
    icon: Notification,
    tone: 'pink'
  }
]
</script>

<template>
  <main class="welcome-page">
    <section class="campus-panel" aria-label="校园社区介绍">
      <div class="campus-panel__content">
        <div class="brand">
          <span class="brand__mark"><el-icon><School /></el-icon></span>
          <span class="brand__name">校园社区</span>
        </div>

        <div class="campus-copy">
          <p class="campus-copy__eyebrow">CAMPUS COMMUNITY</p>
          <h1>一站连接<br>你的校园生活</h1>
          <p class="campus-copy__description">
            从一场讨论到一次活动，从课程安排到校园通知，
            让有用的信息和有趣的人都离你更近。
          </p>
        </div>

        <div class="service-grid">
          <article v-for="service in campusServices" :key="service.title" class="service-item">
            <span :class="['service-item__icon', `service-item__icon--${service.tone}`]">
              <el-icon><component :is="service.icon" /></el-icon>
            </span>
            <div>
              <h2>{{ service.title }}</h2>
              <p>{{ service.description }}</p>
            </div>
          </article>
        </div>

        <p class="campus-panel__footer">校园论坛 · 校园服务 · 同学动态</p>
      </div>
    </section>

    <section class="auth-panel" aria-label="账号入口">
      <div class="mobile-brand">
        <span class="brand__mark"><el-icon><School /></el-icon></span>
        <span class="brand__name">校园社区</span>
      </div>
      <div class="auth-panel__content">
        <router-view v-slot="{ Component }">
          <transition name="auth-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>
      <p class="auth-panel__footer">© 2026 校园社区 · 共建友善校园</p>
    </section>
  </main>
</template>

<style scoped>
.welcome-page {
  --brand: #176bff;
  --brand-dark: #0d2f68;
  --ink: #172033;
  --muted: #6c768b;
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  grid-template-columns: minmax(520px, 1.05fr) minmax(460px, 0.95fr);
  background: #f7f9fc;
  color: var(--ink);
  overflow: hidden;
}

.campus-panel {
  position: relative;
  min-height: 100vh;
  min-height: 100dvh;
  padding: 52px clamp(44px, 6vw, 96px);
  box-sizing: border-box;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.98), rgba(240, 247, 255, 0.96)),
    #f4f8ff;
  border-right: 1px solid #e6ebf3;
  overflow: hidden;
}

.campus-panel::before,
.campus-panel::after {
  content: '';
  position: absolute;
  pointer-events: none;
}

.campus-panel::before {
  width: 310px;
  height: 310px;
  right: -150px;
  top: 15%;
  border: 1px solid rgba(23, 107, 255, 0.13);
  border-radius: 50%;
  box-shadow: 0 0 0 54px rgba(23, 107, 255, 0.025), 0 0 0 108px rgba(23, 107, 255, 0.018);
}

.campus-panel::after {
  inset: 0;
  opacity: 0.35;
  background-image: radial-gradient(#7d9ac5 0.8px, transparent 0.8px);
  background-size: 24px 24px;
  mask-image: linear-gradient(to bottom, transparent 10%, black 55%, transparent 98%);
}

.campus-panel__content {
  position: relative;
  z-index: 1;
  min-height: calc(100vh - 104px);
  min-height: calc(100dvh - 104px);
  display: flex;
  flex-direction: column;
  max-width: 670px;
}

.brand,
.mobile-brand {
  display: inline-flex;
  align-items: center;
  gap: 11px;
}

.brand__mark {
  width: 38px;
  height: 38px;
  display: inline-grid;
  place-items: center;
  border-radius: 8px;
  background: var(--brand);
  color: #fff;
  font-size: 21px;
  box-shadow: 0 8px 22px rgba(23, 107, 255, 0.22);
}

.brand__name {
  font-size: 18px;
  font-weight: 700;
  color: var(--brand-dark);
}

.campus-copy {
  margin-top: clamp(62px, 10vh, 120px);
}

.campus-copy__eyebrow {
  margin: 0 0 15px;
  color: var(--brand);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: 0.14em;
}

.campus-copy h1 {
  margin: 0;
  max-width: 610px;
  font-size: clamp(42px, 4.4vw, 66px);
  line-height: 1.12;
  letter-spacing: 0;
  color: #102954;
}

.campus-copy__description {
  max-width: 560px;
  margin: 24px 0 0;
  color: #61708a;
  font-size: 16px;
  line-height: 1.9;
}

.service-grid {
  width: min(100%, 620px);
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 13px 30px;
  margin-top: 48px;
}

.service-item {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
}

.service-item__icon {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  font-size: 18px;
}

.service-item__icon--blue { color: #176bff; background: #e7f0ff; }
.service-item__icon--orange { color: #d96b18; background: #fff0e4; }
.service-item__icon--green { color: #238460; background: #e5f5ee; }
.service-item__icon--pink { color: #c64b72; background: #fceaf0; }

.service-item h2 {
  margin: 0;
  font-size: 14px;
  line-height: 1.4;
  color: #21334f;
}

.service-item p {
  margin: 3px 0 0;
  color: #7a8598;
  font-size: 12px;
  line-height: 1.4;
}

.campus-panel__footer {
  margin: auto 0 0;
  padding-top: 36px;
  color: #8b96a8;
  font-size: 12px;
}

.auth-panel {
  min-width: 0;
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 48px clamp(34px, 6vw, 88px) 28px;
  box-sizing: border-box;
  background: #fff;
  overflow-y: auto;
}

.auth-panel__content {
  width: 100%;
  max-width: 430px;
  margin: auto 0;
}

.mobile-brand {
  display: none;
  align-self: flex-start;
}

.auth-panel__footer {
  margin: 32px 0 0;
  color: #a0a8b7;
  font-size: 12px;
}

.auth-fade-enter-active,
.auth-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.auth-fade-enter-from {
  opacity: 0;
  transform: translateX(8px);
}

.auth-fade-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}

@media (max-width: 980px) {
  .welcome-page {
    grid-template-columns: minmax(400px, 0.9fr) minmax(420px, 1.1fr);
  }

  .campus-panel {
    padding-inline: 42px;
  }

  .campus-copy h1 {
    font-size: 44px;
  }

  .service-grid {
    grid-template-columns: 1fr;
    gap: 3px;
  }

  .service-item:nth-child(n + 4) {
    display: none;
  }
}

@media (max-width: 840px) {
  .welcome-page {
    display: block;
    overflow: auto;
  }

  .campus-panel {
    display: none;
  }

  .auth-panel {
    min-height: 100vh;
    min-height: 100dvh;
    padding: 28px 24px 22px;
  }

  .mobile-brand {
    display: inline-flex;
  }

  .auth-panel__content {
    max-width: 460px;
    margin: 58px 0 auto;
  }

  .auth-panel__footer {
    margin-top: 48px;
  }
}

@media (max-width: 380px) {
  .auth-panel {
    padding-inline: 18px;
  }
}
</style>
