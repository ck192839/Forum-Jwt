<script setup>
import {Delete, Hide, Lock, Search, Top, User} from "@element-plus/icons-vue";
import {ElMessage, ElMessageBox} from "element-plus";
import {
    apiAgentIndexRebuild,
    apiAgentIndexRebuildStatus,
    apiForumTopicAllList,
    apiForumTopicDelete,
    apiForumTopicInvisible,
    apiForumTopicLocked,
    apiForumTopicTop, apiTopicChangeType
} from "@/net/api/forum";
import {onBeforeUnmount, reactive, ref, watchEffect} from "vue";
import {useStore} from "@/store";

const store = useStore();

const props = defineProps({
    types: Array,
})

const topicList = reactive({
    list: [],
    page: 1,
    size: 10,
    total: 0,
    blocked: 0
})

const keyword = ref('')
const searchText = ref('')
const indexRebuild = reactive({
    running: false,
    total: 0,
    processed: 0,
    failed: 0
})
let indexRebuildTimer = null
let indexRebuildMounted = true

const findType = type => props.types.find(item => item.id === type)

const deleteTopic = id => {
    ElMessageBox.confirm('您确定要删除这个帖子吗，删除后永久无法找回？', '删除帖子', {
        callback: value => {
            if(value === 'confirm') {
                apiForumTopicDelete(id, () => {
                    refreshList()
                    ElMessage.success('帖子删除成功')
                })
            }
        }
    })
}

const topTopic = (tid, status) => {
    apiForumTopicTop({ tid, status }, () => {
        ElMessage.success('帖子置顶状态更新成功')
        refreshList()
    })
}

const lockTopic = (tid, status) => {
    apiForumTopicLocked({ tid, status }, () => {
        ElMessage.success('帖子锁定状态更新成功')
        refreshList()
    })
}

const invisibleTopic = (tid, status) => {
    apiForumTopicInvisible({ tid, status }, () => {
        ElMessage.success('帖子屏蔽状态更新成功')
        refreshList()
    })
}

const changeTopicType = (tid, type) => {
    apiTopicChangeType(tid, type, () => {
        ElMessage.success('帖子类型修改成功')
    })
}

const stopIndexRebuildPolling = () => {
    if(indexRebuildTimer !== null) {
        clearInterval(indexRebuildTimer)
        indexRebuildTimer = null
    }
}

const applyIndexRebuildStatus = status => {
    if(!status || !indexRebuildMounted) return
    Object.assign(indexRebuild, status)
    if(!status.running) {
        stopIndexRebuildPolling()
        if(status.failed > 0) {
            ElMessage.warning(`索引重建完成，${status.failed} 个索引任务失败`)
        } else {
            ElMessage.success(`索引重建完成，共处理 ${status.processed} 篇帖子`)
        }
    }
}

const pollIndexRebuildStatus = () => {
    apiAgentIndexRebuildStatus(
        applyIndexRebuildStatus,
        message => {
            if(!indexRebuildMounted) return
            stopIndexRebuildPolling()
            indexRebuild.running = false
            ElMessage.error(message || '索引重建状态查询失败')
        },
        () => {
            if(!indexRebuildMounted) return
            stopIndexRebuildPolling()
            indexRebuild.running = false
            ElMessage.error('索引重建状态查询失败，请稍后重试')
        }
    )
}

const startIndexRebuild = () => {
    ElMessageBox.confirm(
        '这将清空并重建公开帖子的关键词索引和向量索引，耗时期间搜索可能受到影响，您确定要继续吗？',
        '重建索引',
        { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' }
    ).then(() => {
        if(!indexRebuildMounted) return
        apiAgentIndexRebuild(
            status => {
                if(!indexRebuildMounted) return
                applyIndexRebuildStatus(status)
                if(indexRebuild.running) {
                    stopIndexRebuildPolling()
                    indexRebuildTimer = setInterval(pollIndexRebuildStatus, 1000)
                }
            },
            message => {
                if(!indexRebuildMounted) return
                indexRebuild.running = false
                ElMessage.error(message || '索引重建启动失败')
            },
            () => {
                if(!indexRebuildMounted) return
                indexRebuild.running = false
                ElMessage.error('索引重建启动失败，请稍后重试')
            }
        )
    }).catch(() => {})
}

const refreshList = () => {
    apiForumTopicAllList(topicList.page, topicList.size, keyword.value, data => {
        topicList.list = data.list;
        topicList.total = data.total;
        topicList.blocked = data.blocked ?? 0;
    })
}

watchEffect(() => refreshList())
onBeforeUnmount(() => {
    indexRebuildMounted = false
    stopIndexRebuildPolling()
})
</script>

<template>
    <div class="forum-admin-header">
        <div>
            <div class="title">
                <el-icon><User/></el-icon>
                论坛帖子列表
            </div>
            <div class="desc">
                在这里管理论坛所有的帖子，并对帖子进行各种操作和管理
            </div>
        </div>
        <div class="forum-admin-actions">
            <div class="index-rebuild-control">
                <el-button data-test="rebuild-index"
                           type="warning"
                           :loading="indexRebuild.running"
                           :disabled="indexRebuild.running"
                           @click="startIndexRebuild">
                    重建索引
                </el-button>
                <span v-if="indexRebuild.running || indexRebuild.total > 0"
                      class="index-rebuild-status"
                      role="status">
                    {{ indexRebuild.processed }}/{{ indexRebuild.total }}
                    <span v-if="indexRebuild.failed > 0">，失败 {{ indexRebuild.failed }}</span>
                </span>
            </div>
            <el-input :prefix-icon="Search" placeholder="搜索帖子标题..."
                      clearable @clear="keyword = ''"
                      @keydown.enter="keyword = searchText"
                      v-model="searchText"/>
        </div>
    </div>
    <el-table :data="topicList.list" height="400">
        <el-table-column prop="id" label="帖子ID" width="80" align="center"/>
        <el-table-column prop="title" label="帖子标题" width="300" show-overflow-tooltip>
            <template #default="{ row }">
                <el-link :href="`/index/topic-detail/${row.id}`">
                    <div style="display: inline-flex;gap: 5px;color: dodgerblue;margin-right: 5px">
                        <el-icon v-if="row.locked">
                            <Lock/>
                        </el-icon>
                        <el-icon v-if="row.top">
                            <Top/>
                        </el-icon>
                        <el-icon v-if="row.invisible">
                            <Hide/>
                        </el-icon>
                    </div>
                    {{ row.title }}
                </el-link>
            </template>
        </el-table-column>
        <el-table-column label="帖子类型" width="180">
            <template #default="{ row }">
                <el-select v-model="row.type" @change="id => changeTopicType(row.id, id)">
                    <el-option :label="type.name" :value="type.id" v-for="type in types">
                        <template #default>
                            <div class="topic-type">
                                <div class="type-dot" :style="{ backgroundColor: findType(type.id)?.color ?? '#bababa' }"></div>
                                <div>{{ findType(type.id)?.name ?? '未知类型' }}</div>
                            </div>
                        </template>
                    </el-option>
                </el-select>
            </template>
        </el-table-column>
        <el-table-column label="帖子作者" width="150">
            <template #default="{ row }">
                <div class="topic-username">
                    <el-avatar :size="20" :src="store.avatarUserUrl(row.avatar)"/>
                    <div>{{ row.username }}</div>
                </div>
            </template>
        </el-table-column>
        <el-table-column prop="time" label="发表时间" width="180" align="center"
                         :formatter="row => new Date(row.time).toLocaleString()"/>
        <el-table-column label="操作" width="340" fixed="right" align="center">
            <template #default="{ row }">
                <el-button size="small" type="info" :icon="Hide"
                           @click="invisibleTopic(row.id, false)" v-if="row.invisible">取消</el-button>
                <el-button size="small" type="info" :icon="Hide" plain
                           @click="invisibleTopic(row.id, true)" v-else>屏蔽</el-button>
                <el-button size="small" type="warning" :icon="Lock"
                           @click="lockTopic(row.id, false)" v-if="row.locked">取消</el-button>
                <el-button size="small" type="warning" :icon="Lock" plain
                           @click="lockTopic(row.id, true)" v-else>锁定</el-button>
                <el-button size="small" type="success" :icon="Top"
                           @click="topTopic(row.id, false)" v-if="row.top">取消</el-button>
                <el-button size="small" type="success" :icon="Top" plain
                           @click="topTopic(row.id, true)" v-else>置顶</el-button>
                <el-button size="small" type="danger" :icon="Delete" plain @click="deleteTopic(row.id)">删除</el-button>
            </template>
        </el-table-column>
    </el-table>
    <div class="pagination">
        <div class="blocked-stat" role="status">
            <el-icon><Hide/></el-icon>
            已屏蔽 {{ topicList.blocked }} 篇帖子
        </div>
        <el-pagination :total="topicList.total"
                       v-model:current-page="topicList.page"
                       v-model:page-size="topicList.size"
                       layout="total, sizes, prev, pager, next, jumper"/>
    </div>
</template>

<style lang="less" scoped>
.forum-admin-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.forum-admin-actions {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 12px;
    flex-wrap: wrap;
}

.index-rebuild-control {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 10px;
    flex-wrap: wrap;
}

.index-rebuild-status {
    color: #7a8581;
    font-size: 13px;
    white-space: nowrap;
}

.title {
    font-weight: bold;
}

.desc {
    color: #bababa;
    font-size: 13px;
    margin-bottom: 20px;
}

.pagination {
    margin-top: 20px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 14px;
    flex-wrap: wrap;
}

.blocked-stat {
    display: flex;
    align-items: center;
    gap: 6px;
    color: #b45309;
    font-size: 13px;
}

.dark .blocked-stat {
    color: #d97706;
}

.topic-username {
    gap: 10px;
    display: flex;
    align-items: center;
}

.topic-type {
    gap: 10px;
    display: flex;
    align-items: center;

    .type-dot {
        height: 7px;
        width: 7px;
        border-radius: 50%;
    }
}
</style>
