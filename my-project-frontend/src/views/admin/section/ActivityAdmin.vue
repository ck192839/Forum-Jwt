<script setup>
import {computed, reactive, ref, watchEffect} from "vue";
import {ElMessage, ElMessageBox} from "element-plus";
import {
    apiAdminActivityDelete,
    apiAdminActivityList,
    apiAdminActivitySave,
    apiAdminActivitySetStatus
} from "@/net/api/activity";

const activities = reactive({
    list: [],
    total: 0
})
const page = ref(1)
const size = ref(10)
const keyword = ref('')

const dialog = reactive({
    visible: false,
    saving: false,
    form: null
})

function emptyForm() {
    return {
        id: null,
        title: '',
        description: '',
        location: '',
        activityTime: null,
        totalStock: 20,
        grabStartTime: null,
        grabEndTime: null
    }
}

watchEffect(() => refreshList())

function refreshList() {
    apiAdminActivityList(page.value, size.value, keyword.value || undefined, data => {
        activities.list = data.list
        activities.total = data.total
    })
}

function openCreate() {
    dialog.form = emptyForm()
    dialog.visible = true
}

function openEdit(row) {
    dialog.form = {
        id: row.id,
        title: row.title,
        description: row.description,
        location: row.location,
        activityTime: row.activityTime,
        totalStock: row.totalStock,
        grabStartTime: row.grabStartTime,
        grabEndTime: row.grabEndTime
    }
    dialog.visible = true
}

function save() {
    const form = dialog.form
    if (!form.title || !form.description || !form.location || !form.activityTime
        || !form.grabStartTime || !form.grabEndTime) {
        ElMessage.warning('请完整填写活动信息')
        return
    }
    if (form.grabStartTime >= form.grabEndTime) {
        ElMessage.warning('报名开始时间必须早于报名截止时间')
        return
    }
    if (form.activityTime <= form.grabEndTime) {
        ElMessage.warning('活动时间应晚于报名截止时间')
        return
    }
    dialog.saving = true
    apiAdminActivitySave(form, () => {
        dialog.saving = false
        dialog.visible = false
        ElMessage.success('保存成功')
        refreshList()
    }, message => {
        dialog.saving = false
        ElMessage.warning(message)
    })
}

function toggleStatus(row) {
    const next = row.status === 1 ? 0 : 1
    apiAdminActivitySetStatus(row.id, next, () => {
        ElMessage.success(next === 1 ? '已上架' : '已下架')
        refreshList()
    }, message => ElMessage.warning(message))
}

function remove(row) {
    ElMessageBox.confirm(`确定删除活动「${row.title}」吗？`, '删除确认', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
    }).then(() => {
        apiAdminActivityDelete(row.id, () => {
            ElMessage.success('已删除')
            refreshList()
        }, message => ElMessage.warning(message))
    }).catch(() => {})
}

function formatTime(time) {
    return time ? new Date(time).toLocaleString('zh-CN', {hour12: false}) : '—'
}

const titleLabel = computed(() => dialog.form?.id ? '编辑活动' : '新建活动')
</script>

<template>
    <div class="activity-admin" data-test="activity-admin">
        <div class="toolbar">
            <el-input v-model="keyword" placeholder="按活动标题搜索..." clearable
                      style="width: 220px" data-test="keyword-input"/>
            <el-button type="primary" data-test="create-button" @click="openCreate">新建活动</el-button>
        </div>
        <el-table :data="activities.list" style="width: 100%">
            <el-table-column prop="id" label="ID" width="60"/>
            <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip/>
            <el-table-column label="地点" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.location }}</template>
            </el-table-column>
            <el-table-column label="活动时间" width="160">
                <template #default="{ row }">{{ formatTime(row.activityTime) }}</template>
            </el-table-column>
            <el-table-column label="名额" width="90">
                <template #default="{ row }">{{ row.grabbed }} / {{ row.totalStock }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
                <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'" data-test="status-tag">
                        {{ row.status === 1 ? '上架' : '下架' }}
                    </el-tag>
                </template>
            </el-table-column>
            <el-table-column label="操作" width="220">
                <template #default="{ row }">
                    <el-button size="small" data-test="edit-button" @click="openEdit(row)">编辑</el-button>
                    <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'"
                               data-test="toggle-button" @click="toggleStatus(row)">
                        {{ row.status === 1 ? '下架' : '上架' }}
                    </el-button>
                    <el-button size="small" type="danger" data-test="delete-button" @click="remove(row)">删除</el-button>
                </template>
            </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" :page-size="size" :total="activities.total"
                       layout="prev, pager, next" style="margin-top: 12px;justify-content: flex-end"/>
        <el-dialog v-model="dialog.visible" :title="titleLabel" width="560px" data-test="edit-dialog">
            <el-form v-if="dialog.form" label-width="90px">
                <el-form-item label="标题">
                    <el-input v-model="dialog.form.title" maxlength="50" data-test="form-title"/>
                </el-form-item>
                <el-form-item label="描述">
                    <el-input v-model="dialog.form.description" type="textarea" :rows="3" maxlength="500"/>
                </el-form-item>
                <el-form-item label="地点">
                    <el-input v-model="dialog.form.location" maxlength="100"/>
                </el-form-item>
                <el-form-item label="活动时间">
                    <el-date-picker v-model="dialog.form.activityTime" type="datetime" value-format="x"
                                    placeholder="选择活动时间" style="width: 100%"/>
                </el-form-item>
                <el-form-item label="名额数量">
                    <el-input-number v-model="dialog.form.totalStock" :min="1" :max="100000"/>
                </el-form-item>
                <el-form-item label="报名开始">
                    <el-date-picker v-model="dialog.form.grabStartTime" type="datetime" value-format="x"
                                    placeholder="开始接受报名" style="width: 100%"/>
                </el-form-item>
                <el-form-item label="报名截止">
                    <el-date-picker v-model="dialog.form.grabEndTime" type="datetime" value-format="x"
                                    placeholder="停止接受报名" style="width: 100%"/>
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="dialog.visible = false">取消</el-button>
                <el-button type="primary" :loading="dialog.saving" data-test="save-button" @click="save">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>

<style lang="less" scoped>
.activity-admin {
    display: flex;
    flex-direction: column;
    gap: 14px;
}
.toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 10px;
}
</style>
