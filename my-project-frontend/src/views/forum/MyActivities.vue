<script setup>
import LightCard from "@/components/LightCard.vue";
import {computed, onBeforeUnmount, onMounted, reactive} from "vue";
import {apiActivityMyOrders} from "@/net/api/activity";

/**
 * 报名结果异步落单：存在「排队中」记录时每 2 秒轮询一次，
 * 全部出结果后自动停止；离开页面时清理定时器。
 */
const POLL_INTERVAL = 2000

const orders = reactive({
    list: [],
    loading: true
})

let pollTimer = null
let disposed = false

const hasProcessing = computed(() => orders.list.some(item => item.status === 0))

const STATUS_META = {
    0: {text: '排队中', type: 'warning'},
    1: {text: '报名成功', type: 'success'},
    2: {text: '报名失败', type: 'danger'}
}

function refresh() {
    apiActivityMyOrders(data => {
        if (disposed) return
        orders.list = data
        orders.loading = false
        if (data.some(item => item.status === 0)) {
            startPolling()
        } else {
            stopPolling()
        }
    }, () => {
        if (disposed) return
        orders.loading = false
    }, () => {
        if (disposed) return
        orders.loading = false
    })
}

function startPolling() {
    if (pollTimer) return
    pollTimer = setInterval(refresh, POLL_INTERVAL)
}

function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer)
        pollTimer = null
    }
}

function formatTime(time) {
    return new Date(time).toLocaleString('zh-CN', {hour12: false})
}

onMounted(refresh)
onBeforeUnmount(() => {
    disposed = true
    stopPolling()
})
</script>

<template>
    <div style="margin: 20px auto;max-width: 1080px;padding: 0 20px">
        <div style="display: flex;flex-direction: column;gap: 10px" v-loading="orders.loading">
            <el-empty description="还没有报名记录，去「活动报名」看看吧" v-if="!orders.loading && !orders.list.length"/>
            <light-card v-for="item in orders.list" :key="item.id" class="order-card" data-test="order-card">
                <div class="order-main">
                    <span style="font-weight: bold">{{ item.activityTitle }}</span>
                    <el-tag size="small" :type="STATUS_META[item.status].type" data-test="order-status">
                        {{ STATUS_META[item.status].text }}
                    </el-tag>
                </div>
                <div class="order-time">报名时间：{{ formatTime(item.createTime) }}</div>
            </light-card>
            <div class="polling-hint" v-if="hasProcessing" data-test="polling-hint">报名结果处理中，正在自动刷新…</div>
        </div>
    </div>
</template>

<style scoped>
.order-card {
    display: flex;
    flex-direction: column;
    gap: 6px;
}
.order-main {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 10px;
}
.order-time {
    font-size: 12px;
    color: grey;
}
.polling-hint {
    text-align: center;
    font-size: 12px;
    color: grey;
}
</style>
