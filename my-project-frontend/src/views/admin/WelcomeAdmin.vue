<script setup>
import {
    ArrowRight,
    ChatDotSquare,
    Message,
    User
} from "@element-plus/icons-vue";
import {computed, onMounted, reactive} from "vue";
import {useStore} from "@/store";
import router from "@/router";
import {apiUserList} from "@/net/api/user";
import {apiForumTopicAllList} from "@/net/api/forum";
import {apiEmailFailedTotal} from "@/net/api/email";

const store = useStore()

const totals = reactive({
    users: null,
    topics: null,
    failedEmails: null
})

const hour = new Date().getHours()
const greeting = computed(() => {
    if (hour < 6) return '夜深了'
    if (hour < 12) return '上午好'
    if (hour < 18) return '下午好'
    return '晚上好'
})

const dateLabel = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long'
}).format(new Date())

const metrics = computed(() => [
    {label: '注册用户', value: totals.users, icon: User, tone: 'green'},
    {label: '论坛帖子', value: totals.topics, icon: ChatDotSquare, tone: 'amber'},
    {label: '待处理邮件', value: totals.failedEmails, icon: Message, tone: 'danger'}
])

const shortcuts = [
    {
        title: '用户管理',
        description: '查看账号状态与权限',
        icon: User,
        path: '/admin/user'
    },
    {
        title: '帖子管理',
        description: '处理内容、分类与展示状态',
        icon: ChatDotSquare,
        path: '/admin/forum'
    },
    {
        title: '邮件记录',
        description: '检查发送结果并重试失败邮件',
        icon: Message,
        path: '/admin/email'
    }
]

onMounted(() => {
    apiUserList(1, 1, '', data => totals.users = data.total)
    apiForumTopicAllList(1, 1, '', data => totals.topics = data.total)
    apiEmailFailedTotal(total => totals.failedEmails = total)
})
</script>

<template>
    <div class="admin-overview">
        <header class="overview-intro">
            <div>
                <span class="eyebrow">OPERATIONS OVERVIEW</span>
                <h1>{{ greeting }}，{{ store.user.username || '管理员' }}</h1>
                <p>{{ dateLabel }}</p>
            </div>
        </header>

        <section class="metrics-grid" aria-label="业务数据概览">
            <article v-for="metric in metrics" :key="metric.label" class="metric-item">
                <div :class="['metric-icon', metric.tone]">
                    <el-icon><component :is="metric.icon"/></el-icon>
                </div>
                <div>
                    <div class="metric-label">{{ metric.label }}</div>
                    <div class="metric-value">{{ metric.value ?? '--' }}</div>
                </div>
            </article>
        </section>

        <div class="overview-grid">
            <section class="quick-section">
                <div class="section-heading">
                    <div>
                        <h2>快捷操作</h2>
                        <p>进入常用管理模块</p>
                    </div>
                </div>

                <div class="shortcut-list">
                    <button v-for="item in shortcuts"
                            :key="item.path"
                            type="button"
                            class="shortcut-row"
                            @click="router.push(item.path)">
                        <span class="shortcut-icon">
                            <el-icon><component :is="item.icon"/></el-icon>
                        </span>
                        <span class="shortcut-copy">
                            <strong>{{ item.title }}</strong>
                            <small>{{ item.description }}</small>
                        </span>
                        <el-icon class="shortcut-arrow"><ArrowRight/></el-icon>
                    </button>
                </div>
            </section>

            <aside class="session-panel">
                <div class="section-heading">
                    <div>
                        <h2>当前会话</h2>
                        <p>管理员身份信息</p>
                    </div>
                </div>

                <div class="session-profile">
                    <el-avatar :size="52" :src="store.avatarUrl"/>
                    <div>
                        <strong>{{ store.user.username || '管理员' }}</strong>
                        <span>{{ store.user.email || '尚未加载邮箱' }}</span>
                    </div>
                </div>

                <dl class="session-details">
                    <div>
                        <dt>账号角色</dt>
                        <dd>系统管理员</dd>
                    </div>
                    <div>
                        <dt>可用模块</dt>
                        <dd>3 个</dd>
                    </div>
                </dl>
            </aside>
        </div>
    </div>
</template>

<style lang="less" scoped>
.admin-overview {
    max-width: 1180px;
    margin: 0 auto;
    padding: 36px 30px 48px;
    box-sizing: border-box;
    color: #202825;
}

.overview-intro {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 24px;
    padding-bottom: 28px;
    border-bottom: 1px solid #dfe4e2;

    .eyebrow {
        color: #167d5a;
        font-size: 11px;
        font-weight: 800;
    }

    h1 {
        margin: 8px 0 5px;
        font-size: 28px;
        line-height: 1.25;
        letter-spacing: 0;
    }

    p {
        margin: 0;
        color: #7f8985;
        font-size: 13px;
    }
}

.metrics-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    padding: 24px 0 30px;
}

.metric-item {
    min-height: 112px;
    padding: 22px;
    display: flex;
    align-items: center;
    gap: 16px;
    border: 1px solid #dde3e0;
    border-radius: 6px;
    background: #ffffff;
    box-sizing: border-box;
}

.metric-icon {
    width: 42px;
    height: 42px;
    flex: 0 0 42px;
    display: grid;
    place-items: center;
    border-radius: 6px;
    font-size: 21px;

    &.green {
        color: #126849;
        background: #e5f2ec;
    }

    &.amber {
        color: #9a6414;
        background: #fbefd9;
    }

    &.graphite {
        color: #4e5a56;
        background: #e9edeb;
    }

    &.danger {
        color: #a23b32;
        background: #f8e9e7;
    }
}

.metric-label {
    color: #78827e;
    font-size: 12px;
}

.metric-value {
    margin-top: 3px;
    color: #1d2522;
    font-size: 27px;
    font-weight: 750;
    line-height: 1.1;
}

.overview-grid {
    display: grid;
    grid-template-columns: minmax(0, 1.6fr) minmax(280px, .8fr);
    gap: 18px;
}

.quick-section,
.session-panel {
    padding: 24px;
    border: 1px solid #dde3e0;
    border-radius: 6px;
    background: #ffffff;
}

.section-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 18px;

    h2 {
        margin: 0;
        font-size: 16px;
        letter-spacing: 0;
    }

    p {
        margin: 3px 0 0;
        color: #8b9490;
        font-size: 11px;
    }
}

.shortcut-list {
    border-top: 1px solid #edf0ef;
}

.shortcut-row {
    width: 100%;
    min-height: 74px;
    padding: 14px 4px;
    display: flex;
    align-items: center;
    gap: 14px;
    border: 0;
    border-bottom: 1px solid #edf0ef;
    color: inherit;
    background: transparent;
    cursor: pointer;
    text-align: left;
    transition: background-color .15s ease, padding .15s ease;

    &:hover {
        padding-left: 10px;
        background: #f5f8f6;
    }
}

.shortcut-icon {
    width: 34px;
    height: 34px;
    flex: 0 0 34px;
    display: grid;
    place-items: center;
    border-radius: 6px;
    color: #24694f;
    background: #e9f3ee;
    font-size: 17px;
}

.shortcut-copy {
    min-width: 0;
    flex: 1;
    display: flex;
    flex-direction: column;

    strong {
        font-size: 14px;
    }

    small {
        margin-top: 3px;
        color: #858f8b;
        font-size: 11px;
    }
}

.shortcut-arrow {
    color: #9ba39f;
}

.session-profile {
    padding: 16px 0 22px;
    display: flex;
    align-items: center;
    gap: 14px;
    border-top: 1px solid #edf0ef;
    border-bottom: 1px solid #edf0ef;

    > div {
        min-width: 0;
        display: flex;
        flex-direction: column;
    }

    strong {
        font-size: 15px;
    }

    span {
        margin-top: 3px;
        overflow: hidden;
        color: #838d89;
        font-size: 11px;
        text-overflow: ellipsis;
        white-space: nowrap;
    }
}

.session-details {
    margin: 12px 0 0;

    > div {
        min-height: 34px;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 18px;
    }

    dt {
        color: #8a938f;
        font-size: 12px;
    }

    dd {
        margin: 0;
        color: #35403c;
        font-size: 12px;
        font-weight: 700;
    }

}

.dark {
    .admin-overview {
        color: #edf1ef;
    }

    .overview-intro,
    .shortcut-list,
    .shortcut-row,
    .session-profile {
        border-color: #373d3a;
    }

    .metric-item,
    .quick-section,
    .session-panel {
        border-color: #373d3a;
        background: #202422;
    }

    .metric-value,
    .session-details dd {
        color: #edf1ef;
    }

    .shortcut-row:hover {
        background: #282e2b;
    }
}

@media (max-width: 900px) {
    .overview-grid {
        grid-template-columns: 1fr;
    }
}

@media (max-width: 680px) {
    .admin-overview {
        padding: 24px 16px 36px;
    }

    .overview-intro {
        align-items: flex-start;
        flex-direction: column;

        h1 {
            font-size: 23px;
        }
    }

    .metrics-grid {
        grid-template-columns: 1fr;
    }

    .metric-item {
        min-height: 88px;
        padding: 16px;
    }

    .quick-section,
    .session-panel {
        padding: 18px;
    }
}
</style>
