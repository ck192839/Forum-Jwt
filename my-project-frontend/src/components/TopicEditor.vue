<script setup>
import {Check, Document, MagicStick} from "@element-plus/icons-vue";
import {computed, nextTick, reactive, ref, watch} from "vue";
import {Delta, Quill, QuillEditor} from "@vueup/vue-quill";
import ImageResize from "quill-image-resize-vue";
import { ImageExtend, QuillWatch } from "quill-image-super-solution-module";
import '@vueup/vue-quill/dist/vue-quill.snow.css';
import axios from "axios";
import {accessHeader} from "@/net";
import {ElMessage} from "element-plus";
import ColorDot from "@/components/ColorDot.vue";
import {useStore} from "@/store";
import {apiForumTopicCreate} from "@/net/api/forum";
import {deltaToModelText} from "@/agent/deltaText";
import {currentEditorVersion, nextEditorVersion, stableEditorId} from "@/agent/editorIdentity";
import {
    consumeDraftApplication,
    pendingDraftApplication,
    requestEditorOptimization
} from "@/agent/editorBridge";

const store = useStore()

const props = defineProps({
    show: Boolean,
    defaultTitle: {
        default: '',
        type: String
    },
    defaultText: {
        default: '',
        type: String
    },
    defaultType: {
        default: null,
        type: Number
    },
    acceptUntargetedDraft: {
        default: false,
        type: Boolean
    },
    editorKey: {
        default: 'new-topic',
        type: String
    },
    submitButton: {
        default: '立即发表主题',
        type: String
    },
    submit: {
        default: (editor, success) => {
            apiForumTopicCreate({
                type: editor.type.id,
                title: editor.title,
                content: editor.text
            }, () => {
                ElMessage.success("帖子发表成功！")
                success()
            })
        },
        type: Function
    }
})

const emit = defineEmits(['close', 'success'])

const refEditor = ref()
const editorId = stableEditorId(props.editorKey)
const isEditMode = computed(() => props.editorKey !== 'new-topic')
let suppressVersionTracking = false
let editorReady = false
const editor = reactive({
    type: null,
    title: '',
    text: '',
    loading: false,
    version: 0
})
const aiPreview = reactive({
    visible: false,
    data: null
})

function initEditor() {
    editorReady = false
    suppressVersionTracking = true
    if(!refEditor.value || typeof refEditor.value.getQuill !== 'function' || !refEditor.value.getQuill()) {
        nextTick(() => initEditor())
        return
    }
    const quillRoot = refEditor.value.getQuill().root
    quillRoot?.setAttribute('role', 'textbox')
    quillRoot?.setAttribute('aria-label', '帖子正文')
    quillRoot?.setAttribute('aria-multiline', 'true')
    if(props.defaultText)
        editor.text = new Delta(JSON.parse(props.defaultText))
    else
        refEditor.value.setContents('', 'user')
    editor.title = props.defaultTitle
    editor.type = findTypeById(props.defaultType)
    nextTick(() => {
        editor.version = currentEditorVersion(props.editorKey)
        suppressVersionTracking = false
        editorReady = true
        openPendingDraftPreview()
    })
}

function deltaToText(delta) {
    return deltaToModelText(delta).replace(/\s/g, "")
}

const contentLength = computed(() => deltaToText(editor.text).length)

function findTypeById(id){
    for (let type of store.forum.types) {
        if(type.id === id)
            return type
    }
}

function submitTopic() {
    const text = deltaToText(editor.text)
    if(text.length > 20000) {
        ElMessage.warning('字数超出限制，无法发布主题！')
        return
    }
    if(!editor.title) {
        ElMessage.warning('请填写标题！')
        return
    }
    if(!editor.type) {
        ElMessage.warning('请选择一个合适的帖子类型！')
        return
    }
    props.submit(editor, () => emit('success'))
}

function touchEditor() {
    if(!suppressVersionTracking)
        editor.version = nextEditorVersion(props.editorKey)
}

function requestAiOptimization() {
    const bodyMarkdown = deltaToModelText(editor.text)
    if(!editor.title?.trim() && !editor.type && !bodyMarkdown.trim()) {
        ElMessage.warning('请先输入需要优化的内容')
        return
    }
    requestEditorOptimization({
        editorId,
        editorVersion: editor.version,
        editorDraft: {
            title: editor.title || null,
            topicTypeId: editor.type?.id || null,
            bodyMarkdown: bodyMarkdown || null
        }
    })
    emit('close')
}

async function openPendingDraftPreview() {
    if(!props.show || !editorReady) return
    const draft = consumeDraftApplication(editorId, props.acceptUntargetedDraft, editor.version)
    if(!draft) return
    try {
        const {createDraftPreview} = await import("@/agent/draftApply")
        aiPreview.data = createDraftPreview({
            title: editor.title,
            topicTypeId: editor.type?.id || null,
            bodyText: deltaToModelText(editor.text),
            editorVersion: editor.version
        }, draft)
        aiPreview.visible = true
    } catch (error) {
        ElMessage.warning(error.message)
    }
}

async function applyAgentDraft() {
    const preview = aiPreview.data
    if(!preview) return
    try {
        const {assertDraftVersion, markdownToSafeDelta} = await import("@/agent/draftApply")
        assertDraftVersion(preview.draft, editor.version)
        const draftType = findTypeById(preview.draft.topicTypeId)
        if(!draftType) {
            ElMessage.warning('Agent 推荐的板块已不可用，请重新生成草稿')
            return
        }
        const quill = refEditor.value.getQuill()
        const nextDelta = markdownToSafeDelta(
            preview.draft.bodyMarkdown,
            quill.clipboard,
            editor.text
        )
        const nextContent = new Delta(nextDelta)
        const previous = {
            title: editor.title,
            type: editor.type,
            text: cloneDelta(editor.text),
            version: editor.version
        }
        const nextVersion = nextEditorVersion(props.editorKey)
        suppressVersionTracking = true
        try {
            refEditor.value.setContents(nextContent, 'api')
            editor.title = preview.draft.title
            editor.type = draftType
            editor.text = nextContent
            editor.version = nextVersion
        } catch (error) {
            try {
                refEditor.value.setContents(previous.text, 'api')
            } catch {
                // The reactive snapshot still prevents a partial title/type/version commit.
            }
            editor.title = previous.title
            editor.type = previous.type
            editor.text = previous.text
            editor.version = previous.version
            throw error
        } finally {
            await nextTick()
            suppressVersionTracking = false
        }
        aiPreview.visible = false
        ElMessage.success('Agent 草稿已应用到编辑器')
    } catch (error) {
        ElMessage.warning(error.message)
    }
}

function typeName(id) {
    if(id == null) return '（未选择）'
    return findTypeById(id)?.name || `#${id}`
}

function cloneDelta(delta) {
    const operations = JSON.parse(JSON.stringify(delta?.ops || []))
    return new Delta({ops: operations})
}

watch(pendingDraftApplication, openPendingDraftPreview, {flush: 'post'})
watch(() => props.show, show => {
    if(show) {
        initEditor()
    } else {
        editorReady = false
    }
}, {immediate: true})

Quill.register('modules/imageResize', ImageResize)
Quill.register('modules/ImageExtend', ImageExtend)
const editorOption = {
    modules: {
        toolbar: {
            container: [
                "bold", "italic", "underline", "strike","clean",
                {color: []}, {'background': []},
                {size: ["small", false, "large", "huge"]},
                { header: [1, 2, 3, 4, 5, 6, false] },
                {list: "ordered"}, {list: "bullet"}, {align: []},
                "blockquote", "code-block", "link", "image",
                { indent: '-1' }, { indent: '+1' }
            ],
            handlers: {
                'image': function () {
                    QuillWatch.emit(this.quill.id)
                }
            }
        },
        imageResize: {
            modules: [ 'Resize', 'DisplaySize' ]
        },
        ImageExtend: {
            action:  axios.defaults.baseURL + '/api/image/cache',
            name: 'file',
            size: 5,
            loading: true,
            accept: 'image/png, image/jpeg',
            response: (resp) => {
                if(resp.data) {
                    return axios.defaults.baseURL + '/images' + resp.data
                } else {
                    return null
                }
            },
            methods: 'POST',
            headers: xhr => {
                xhr.setRequestHeader('Authorization', accessHeader().Authorization);
            },
            start: () => editor.uploading = true,
            success: () => {
                ElMessage.success('图片上传成功!')
                editor.uploading = false
            },
            error: () => {
                ElMessage.warning('图片上传失败，请联系管理员!')
                editor.uploading = false
            }
        }
    }
}
</script>

<template>
  <el-drawer :model-value="show"
             aria-label="发布帖子编辑器"
             direction="btt"
             :close-on-click-modal="false"
             :size="650"
             @close="emit('close')">
    <template #header>
      <div>
        <div style="font-weight: bold">{{isEditMode ? '编辑帖子' : '发表新的帖子'}}</div>
        <div style="font-size: 13px">{{isEditMode ? '修改帖子内容，请遵守相关法律法规' : '发表内容之前，请遵守相关法律法规，不要出现骂人等爆粗口的不文明行为。'}}</div>
      </div>
    </template>
    <div style="display: flex;gap: 10px">
      <div style="width: 150px">
        <el-select placeholder="选择主题类型..." aria-label="帖子板块" value-key="id" v-model="editor.type"
                   :disabled="!store.forum.types.length" @change="touchEditor">
          <el-option v-for="item in store.forum.types.filter(type => type.id > 0)" :value="item" :label="item.name">
            <div>
              <color-dot :color="item.color"/>
              <span style="margin-left: 10px">{{item.name}}</span>
            </div>
          </el-option>
        </el-select>
      </div>
      <div style="flex: 1">
        <el-input v-model="editor.title" aria-label="帖子标题" placeholder="请输入帖子标题..." :prefix-icon="Document" @input="touchEditor"
                  style="height: 100%" maxlength="30"/>
      </div>
    </div>
    <div style="margin-top: 5px;font-size: 13px;color: grey">
      <color-dot :color="editor.type ? editor.type.color : '#dedede'"/>
      <span style="margin-left: 5px">{{editor.type ? editor.type.desc : '请在上方选择一个帖子类型'}}</span>
    </div>
    <div style="margin-top: 10px;height: 440px;overflow: hidden;border-radius: 5px"
         v-loading="editor.uploading"
         element-loading-text="这种上传图片，请稍后...">
      <quill-editor v-model:content="editor.text" style="height: calc(100% - 45px)"
                    content-type="delta" ref="refEditor"
                    placeholder="今天想分享点什么呢？" :options="editorOption"
                    @text-change="touchEditor"/>
    </div>
    <div style="display: flex;justify-content: space-between;margin-top: 5px">
      <div style="color: grey;font-size: 13px">
        当前字数 {{contentLength}}（最大支持20000字）
      </div>
      <div class="editor-actions">
        <el-button type="primary" :icon="MagicStick" @click="requestAiOptimization" plain>AI 优化</el-button>
        <el-button type="success" :icon="Check" @click="submitTopic" plain>{{submitButton}}</el-button>
      </div>
    </div>
    <el-dialog v-model="aiPreview.visible" title="应用 Agent 草稿" width="min(720px, 92vw)" append-to-body>
      <div v-if="aiPreview.data" class="agent-diff">
        <div class="diff-field" v-if="aiPreview.data.title.changed">
          <span>标题</span>
          <del>{{aiPreview.data.title.before || '（空）'}}</del>
          <ins>{{aiPreview.data.title.after}}</ins>
        </div>
        <div class="diff-field" v-if="aiPreview.data.topicTypeId.changed">
          <span>板块</span>
          <del>{{typeName(aiPreview.data.topicTypeId.before)}}</del>
          <ins>{{typeName(aiPreview.data.topicTypeId.after)}}</ins>
        </div>
        <div class="diff-body" role="region" aria-label="正文差异">
          <span v-for="(part, index) in aiPreview.data.body" :key="index"
                :class="{added: part.added, removed: part.removed}">{{part.value}}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="aiPreview.visible = false">取消</el-button>
        <el-button type="primary" @click="applyAgentDraft">应用到编辑器</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<style scoped>
:deep(.el-drawer) {
    width: 800px;
    margin: auto;
    border-radius: 10px 10px 0 0;
}
:deep(.el-drawer__header) {
    margin: 0;
}
.editor-actions {
    display: flex;
    gap: 8px;
}
.agent-diff {
    display: grid;
    gap: 14px;
}
.diff-field {
    display: grid;
    grid-template-columns: 64px 1fr 1fr;
    gap: 10px;
    align-items: start;
}
.diff-field > span {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.diff-field del,
.diff-field ins {
    padding: 7px;
    border-radius: 5px;
    text-decoration: none;
    overflow-wrap: anywhere;
}
.diff-field del,
.diff-body .removed {
    background: color-mix(in srgb, #ef4444 12%, var(--el-bg-color));
}
.diff-field ins,
.diff-body .added {
    background: color-mix(in srgb, #22c55e 12%, var(--el-bg-color));
}
.diff-body {
    max-height: 320px;
    padding: 10px;
    overflow: auto;
    border: 1px solid var(--el-border-color);
    border-radius: 5px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
    font-size: 13px;
}
.diff-body span {
    display: inline;
}
</style>
