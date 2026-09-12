<script setup>
import {
    AlarmClock,
    ChatDotSquare,
    HomeFilled,
    Message,
    Setting,
    User
} from "@element-plus/icons-vue";
import UserInfo from "@/components/UserInfo.vue";
import {computed, inject} from "vue";
import {useRoute} from "vue-router";

const route = useRoute()
const loading = inject('userLoading')

const navigation = [
    {
        label: '工作台',
        items: [
            {title: '管理概览', icon: HomeFilled, index: '/admin'}
        ]
    },
    {
        label: '运营管理',
        items: [
            {title: '用户管理', icon: User, index: '/admin/user'},
            {title: '帖子管理', icon: ChatDotSquare, index: '/admin/forum'},
            {title: '活动管理', icon: AlarmClock, index: '/admin/activity'},
            {title: '邮件记录', icon: Message, index: '/admin/email'}
        ]
    }
]

const currentPage = computed(() => navigation
    .flatMap(group => group.items)
    .find(item => item.index === route.path) ?? navigation[0].items[0])
</script>

<template>
    <div class="admin-shell" v-loading="loading" element-loading-text="正在进入管理端...">
        <el-container class="admin-layout">
            <el-aside width="248px" class="admin-aside">
                <router-link class="brand" to="/admin" aria-label="返回管理概览">
                    <span class="brand-mark">
                        <el-icon><Setting/></el-icon>
                    </span>
                    <span class="brand-copy">
                        <strong>Campus Console</strong>
                        <small>校园社区管理</small>
                    </span>
                </router-link>

                <el-scrollbar class="nav-scrollbar">
                    <nav class="admin-nav" aria-label="管理端导航">
                        <section v-for="group in navigation" :key="group.label" class="nav-group">
                            <div class="section-label">{{ group.label }}</div>
                            <el-menu router :default-active="route.path" class="nav-menu">
                                <el-menu-item v-for="item in group.items"
                                              :key="item.index"
                                              :index="item.index">
                                    <el-icon><component :is="item.icon"/></el-icon>
                                    <span class="menu-label">{{ item.title }}</span>
                                </el-menu-item>
                            </el-menu>
                        </section>
                    </nav>
                </el-scrollbar>

            </el-aside>

            <el-container class="admin-stage">
                <el-header class="admin-header">
                    <div class="page-heading">
                        <span>管理控制台</span>
                        <strong>{{ currentPage.title }}</strong>
                    </div>
                    <user-info/>
                </el-header>

                <el-main class="admin-main">
                    <el-scrollbar class="main-scrollbar">
                        <router-view v-slot="{ Component }">
                            <transition name="admin-page" mode="out-in">
                                <component :is="Component"/>
                            </transition>
                        </router-view>
                    </el-scrollbar>
                </el-main>
            </el-container>
        </el-container>
    </div>
</template>

<style lang="less" scoped>
.admin-shell,
.admin-layout {
    width: 100vw;
    height: 100vh;
    overflow: hidden;
}

.admin-aside {
    display: flex;
    flex-direction: column;
    border-right: 1px solid #dde2e5;
    background: #ffffff;
    transition: width .2s ease;
}

.brand {
    height: 72px;
    padding: 0 20px;
    display: flex;
    align-items: center;
    gap: 12px;
    box-sizing: border-box;
    color: #1f2926;
    text-decoration: none;
    border-bottom: 1px solid #edf0f1;
}

.brand-mark {
    width: 34px;
    height: 34px;
    flex: 0 0 34px;
    display: grid;
    place-items: center;
    border-radius: 6px;
    color: #ffffff;
    background: #167d5a;
    font-size: 19px;
}

.brand-copy {
    min-width: 0;
    display: flex;
    flex-direction: column;
    line-height: 1.2;

    strong {
        font-size: 15px;
        white-space: nowrap;
    }

    small {
        margin-top: 4px;
        color: #7a8581;
        font-size: 11px;
    }
}

.nav-scrollbar {
    flex: 1;
}

.admin-nav {
    padding: 18px 12px;
}

.nav-group + .nav-group {
    margin-top: 22px;
}

.section-label {
    padding: 0 12px 7px;
    color: #929b98;
    font-size: 11px;
    font-weight: 700;
}

.nav-menu {
    border-right: 0;
    background: transparent;
}

:deep(.el-menu-item) {
    height: 42px;
    margin: 3px 0;
    border-radius: 6px;
    color: #505a57;
}

:deep(.el-menu-item:hover) {
    background: #f1f5f3;
}

:deep(.el-menu-item.is-active) {
    color: #126849;
    background: #e9f4ef;
    font-weight: 700;
}

.admin-stage {
    min-width: 0;
}

.admin-header {
    height: 72px;
    padding: 0 28px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 20px;
    border-bottom: 1px solid #dde2e5;
    background: rgba(255, 255, 255, .96);
    box-sizing: border-box;
}

.page-heading {
    min-width: 0;
    display: flex;
    flex-direction: column;
    line-height: 1.25;

    span {
        color: #929b98;
        font-size: 11px;
    }

    strong {
        margin-top: 3px;
        color: #202825;
        font-size: 17px;
    }
}

:deep(.user-info) {
    width: auto;
}

.admin-main {
    height: calc(100vh - 72px);
    padding: 0;
    overflow: hidden;
    background: #f4f6f5;
}

.main-scrollbar {
    height: 100%;
}

:deep(.el-scrollbar__view) {
    min-height: 100%;
}

:deep(.user-admin),
:deep(.email-admin),
:deep(.forum-admin) {
    max-width: 1320px;
    margin: 0 auto;
    padding: 28px;
    box-sizing: border-box;
}

.admin-page-enter-active,
.admin-page-leave-active {
    transition: opacity .16s ease, transform .16s ease;
}

.admin-page-enter-from,
.admin-page-leave-to {
    opacity: 0;
    transform: translateY(4px);
}

.dark {
    .admin-aside,
    .admin-header {
        border-color: #343a38;
        background: #1d211f;
    }

    .brand {
        border-color: #343a38;
    }

    .brand,
    .page-heading strong {
        color: #edf1ef;
    }

    .admin-main {
        background: #171a19;
    }

}

:global(.dark) :deep(.el-menu-item:hover) {
    background: #282e2b;
}

:global(.dark) :deep(.el-menu-item.is-active) {
    color: #80d7b4;
    background: #213d31;
}

@media (max-width: 820px) {
    .admin-aside {
        width: 72px !important;
    }

    .brand {
        padding: 0;
        justify-content: center;
    }

    .brand-copy,
    .section-label,
    .menu-label {
        display: none;
    }

    .admin-nav {
        padding: 14px 9px;
    }

    :deep(.el-menu-item) {
        padding: 0 !important;
        justify-content: center;
    }

    :deep(.el-menu-item .el-icon) {
        margin: 0;
    }

    .admin-header {
        padding: 0 16px;
    }

    :deep(.user-info .profile) {
        display: none;
    }

    :deep(.user-info) {
        gap: 10px;
    }

    :deep(.user-admin),
    :deep(.email-admin),
    :deep(.forum-admin) {
        padding: 18px;
    }
}

@media (max-width: 560px) {
    .page-heading span,
    :deep(.user-info > .el-button) {
        display: none;
    }

    .page-heading strong {
        margin-top: 0;
        font-size: 15px;
    }
}
</style>
