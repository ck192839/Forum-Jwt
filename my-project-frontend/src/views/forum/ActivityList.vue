<script setup>
import LightCard from "@/components/LightCard.vue";
import {Location, Clock, User, AlarmClock} from "@element-plus/icons-vue";
import {computed, onMounted, reactive} from "vue";
import {ElMessage} from "element-plus";
import {apiActivityList, apiActivityGrab} from "@/net/api/activity";
import router from "@/router";

const activities = reactive({
    list: [],
    loading: true
})

onMounted(() => {
    apiActivityList(data => {
        activities.list = data
        activities.loading = false
    }, () => activities.loading = false)
})

function formatTime(time) {
    return new Date(time).toLocaleString('zh-CN', {hour12: false})
}

function remaining(item) {
    return Math.max(item.totalStock - item.grabbed, 0)
}

/**
 * 报名窗口状态：not-started 未开始 / active 报名中 / ended 已结束。
 * 名额是否抢完单独由 remaining 计算，两者共同决定按钮可用性。
 */
const phaseOf = item => {
    const now = Date.now()
    if (new Date(item.grabStartTime).getTime() > now) return 'not-started'
    if (new Date(item.grabEndTime).getTime() < now) return 'ended'
    return 'active'
}

const phaseText = { 'not-started': '未开始', 'active': '报名中', 'ended': '已结束' }

function grab(item) {
    apiActivityGrab({activityId: item.id}, message => {
        ElMessage.success(message)
        router.push('/index/my-activities')
    }, message => ElMessage.warning(message))
}
</script>

<template>
    <div style="margin: 20px auto;max-width: 1080px;padding: 0 20px">
        <div style="display: flex;flex-direction: column;gap: 10px" v-loading="activities.loading">
            <el-empty description="暂时没有可报名的活动" v-if="!activities.loading && !activities.list.length"/>
            <light-card v-for="item in activities.list" :key="item.id" class="activity-card">
                <div class="activity-head">
                    <div class="activity-title">
                        <el-tag size="small" :type="phaseOf(item) === 'active' ? 'success' : 'info'">
                            {{ phaseText[phaseOf(item)] }}
                        </el-tag>
                        <span style="font-weight: bold;font-size: 16px">{{ item.title }}</span>
                    </div>
                    <div class="grab-action">
                        <span class="quota" :class="{scarce: remaining(item) > 0 && remaining(item) <= 5}">
                            剩余 {{ remaining(item) }} / {{ item.totalStock }}
                        </span>
                        <el-button type="primary" :icon="AlarmClock" data-test="grab-button"
                                   :disabled="phaseOf(item) !== 'active' || remaining(item) === 0"
                                   @click="grab(item)">
                            {{ remaining(item) === 0 ? '已抢完' : '立即报名' }}
                        </el-button>
                    </div>
                </div>
                <div class="activity-desc">{{ item.description }}</div>
                <div class="activity-meta">
                    <span><el-icon><Location/></el-icon>{{ item.location }}</span>
                    <span><el-icon><Clock/></el-icon>活动时间：{{ formatTime(item.activityTime) }}</span>
                    <span><el-icon><User/></el-icon>报名时间：{{ formatTime(item.grabStartTime) }} 起</span>
                </div>
            </light-card>
        </div>
    </div>
</template>

<style scoped>
.activity-card {
    display: flex;
    flex-direction: column;
    gap: 10px;
}
.activity-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
}
.activity-title {
    display: flex;
    align-items: center;
    gap: 8px;
}
.grab-action {
    display: flex;
    align-items: center;
    gap: 10px;
}
.quota {
    font-size: 13px;
    color: grey;
}
.quota.scarce {
    color: #e6a23c;
    font-weight: bold;
}
.activity-desc {
    font-size: 13px;
    color: grey;
}
.activity-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 15px;
    font-size: 12px;
    color: grey;
}
.activity-meta span {
    display: inline-flex;
    align-items: center;
    gap: 4px;
}
</style>
