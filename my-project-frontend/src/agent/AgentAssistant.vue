<script setup>
import { computed, ref, watch } from 'vue'
import {
  Check,
  Close,
  Delete,
  Loading,
  MagicStick,
  Plus,
  Promotion,
  School,
  VideoPause
} from '@element-plus/icons-vue'
import { createAgentAssistantController } from './useAgentAssistant'
import { sanitizeMarkdown } from './draftApply'
import router from '@/router'
import {
  editorOptimizationRequest,
  publishDraftApplication,
  requestEditorOpen
} from './editorBridge'

const emit = defineEmits(['apply-draft'])
const open = ref(false)
const controller = createAgentAssistantController()
const {
  state,
  sessions,
  prompt,
  loading,
  submitting,
  initialize,
  selectSession,
  newSession,
  deleteCurrentSession,
  submit,
  cancel
} = controller

let optimizationEditorId = null

const canSend = computed(() => prompt.value.trim().length > 0 && !submitting.value)
const visibleMessages = computed(() =>
  state.messages.filter(message =>
    message.role !== 'ASSISTANT' || !isTerminalJson(message.content)
  )
)

function isTerminalJson(content) {
  if (typeof content !== 'string' || !content.trim().startsWith('{')) return false
  try {
    const parsed = JSON.parse(content)
    return !!parsed && typeof parsed === 'object'
      && (parsed.type === 'DRAFT' || parsed.type === 'QUESTION' || 'bodyMarkdown' in parsed)
  } catch {
    return false
  }
}

// 助手消息按 markdown 渲染（sanitizeMarkdown 内部经 DOMPurify 消毒）
function assistantHtml(content) {
  return sanitizeMarkdown(content)
}

// markdown 里的站内链接用 router 跳转，避免整页刷新
function onMessageClick(event) {
  const link = event.target.closest('a')
  if (!link) return
  const href = link.getAttribute('href') || ''
  if (href.startsWith('/')) {
    event.preventDefault()
    router.push(href)
  }
}

const toolLabels = {
  list_topic_types: '获取板块',
  search_similar_topics: '查找相似帖子',
  read_public_topic: '阅读公开帖子',
  validate_draft: '校验草稿'
}

async function openAssistant() {
  open.value = true
  await initialize()
}

function applyDraft(draft) {
  publishDraftApplication(draft)
  const target = draft.targetEditorId
  let targetPage = null
  let openEditorId = target
  if (target == null) {
    targetPage = '/index'
    openEditorId = 'topic-editor:new-topic'
  } else if (target.startsWith('topic-editor:topic-')) {
    targetPage = `/index/topic-detail/${decodeURIComponent(target.slice('topic-editor:topic-'.length))}`
  } else if (target === 'topic-editor:new-topic') {
    targetPage = '/index'
  } else {
    openEditorId = null
  }
  if (targetPage && router.currentRoute.value.path !== targetPage) {
    router.push(targetPage)
  }
  if (openEditorId) {
    requestEditorOpen(openEditorId)
  }
  emit('apply-draft', draft)
}

function sessionLabel(session) {
  if (session.title) return session.title
  const date = session.updatedAt ? new Date(session.updatedAt).toLocaleString('zh-CN') : `#${session.id}`
  return session.status === 'ACTIVE' ? `${date} · 进行中` : date
}

// 告知显示到分（恢复历史会话时可能带出非当天的旧告知，故带上日期）
function formatNoticeTime(at) {
  const date = new Date(at)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function submitOnShortcut(event) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && canSend.value) {
    event.preventDefault()
    submit()
  }
}

watch(editorOptimizationRequest, async request => {
  if (!request) return
  open.value = true
  await initialize()
  const newTopic = request.editorId === 'topic-editor:new-topic'
  if (request.editorId && (newTopic || request.editorId !== optimizationEditorId)) {
    await newSession()
  }
  if (request.editorId) {
    optimizationEditorId = request.editorId
  }
  await submit({
    editorId: request.editorId,
    editorVersion: request.editorVersion,
    editorDraft: request.editorDraft
  })
})

async function resetAndNewSession() {
  optimizationEditorId = null
  await newSession()
}
</script>

<template>
  <div class="agent-assistant">
    <button
      class="agent-launcher"
      type="button"
      title="打开发帖助手"
      aria-label="打开发帖助手"
      @click="openAssistant">
      <MagicStick/>
    </button>

    <transition name="agent-panel">
      <aside v-if="open" class="agent-panel" aria-label="发帖助手">
        <header class="agent-header">
          <div class="agent-brand">
            <span class="agent-brand__mark"><School/></span>
            <div>
              <h2>发帖助手</h2>
              <span :class="['run-state', state.runStatus]">
                {{ submitting ? '处理中' : '就绪' }}
              </span>
            </div>
          </div>
          <button type="button" class="icon-button" title="关闭" aria-label="关闭" @click="open = false">
            <Close/>
          </button>
        </header>

        <div class="session-toolbar">
          <select
            :value="state.sessionId ?? ''"
            aria-label="Agent 会话"
            :disabled="loading"
            @change="selectSession(Number($event.target.value))">
            <option value="" disabled>选择会话</option>
            <option v-for="session in sessions" :key="session.id" :value="session.id">
              {{ sessionLabel(session) }}
            </option>
          </select>
          <button type="button" class="icon-button" title="新建会话" aria-label="新建会话"
                  @click="resetAndNewSession">
            <Plus/>
          </button>
          <button type="button" class="icon-button danger" title="删除会话" aria-label="删除会话"
                  :disabled="state.sessionId == null || submitting" @click="deleteCurrentSession">
            <Delete/>
          </button>
        </div>

        <main class="agent-content" aria-live="polite">
          <div v-if="loading" class="center-state"><Loading class="spin"/>加载中</div>
          <template v-else>
            <div v-if="!state.messages.length && !state.streamingText" class="empty-state">
              问我论坛内容相关的问题，或描述你准备发布的内容
            </div>

            <!-- 上下文治理告知：run 内触发降级（上下文裁剪/工具预算耗尽）时后端推送，
                 按 run 生命周期展示（新 run 开始即清空），带触发时间便于区分新旧 -->
            <el-alert
              v-for="(notice, index) in state.notices"
              :key="`${notice.at}-${index}`"
              type="warning"
              :closable="true"
              class="context-notice"
              @close="state.notices.splice(index, 1)"
            >
              <template #title>
                <span class="context-notice-text">{{ notice.text }}</span>
                <time class="context-notice-time">{{ formatNoticeTime(notice.at) }}</time>
              </template>
            </el-alert>

            <div v-for="(message, index) in visibleMessages" :key="message.id || index"
                 :class="['message', message.role.toLowerCase()]">
              <template v-if="message.role === 'USER'">{{ message.content }}</template>
              <template v-else>
                <div v-if="message.timeline && message.timeline.length" class="message-tools">
                  <div v-for="(tool, toolIndex) in message.timeline" :key="toolIndex" class="tool-row">
                    <Check class="tool-complete"/>
                    <span>{{ toolLabels[tool.toolName] || tool.toolName }}</span>
                  </div>
                </div>
                <!-- eslint-disable-next-line vue/no-v-html — 内容已经 sanitizeMarkdown 消毒 -->
                <div class="message-body" v-html="assistantHtml(message.content)" @click="onMessageClick"></div>
                <nav v-if="message.citations && message.citations.length" class="citation-list message-citations"
                     aria-label="本轮引用帖子">
                  <RouterLink v-for="citation in message.citations" :key="citation.topicId"
                              :to="`/index/topic-detail/${citation.topicId}`">
                    <span>#{{ citation.topicId }}</span>{{ citation.title }}
                  </RouterLink>
                </nav>
              </template>
            </div>

            <!-- 当前 run 的工具执行与引用：渲染在流式回答上方，跟随会话流而不是沉到底部 -->
            <section v-if="state.timeline.length" class="tool-timeline" aria-label="工具执行状态">
              <div v-for="(tool, index) in state.timeline" :key="`${tool.runId}-${tool.toolName}-${index}`"
                   class="tool-row" role="status"
                   :aria-label="`${toolLabels[tool.toolName] || tool.toolName}：${tool.status === 'completed' ? '已完成' : '执行中'}`">
                <Check v-if="tool.status === 'completed'" class="tool-complete"/>
                <Loading v-else class="spin"/>
                <span>{{ toolLabels[tool.toolName] || tool.toolName }}</span>
              </div>
            </section>

            <nav v-if="state.citations.length" class="citation-list" aria-label="引用帖子">
              <RouterLink v-for="citation in state.citations" :key="citation.topicId"
                          :to="`/index/topic-detail/${citation.topicId}`">
                <span>#{{ citation.topicId }}</span>{{ citation.title }}
              </RouterLink>
            </nav>

            <div v-if="state.streamingText" class="message assistant streaming">
              <!-- eslint-disable-next-line vue/no-v-html — 内容已经 sanitizeMarkdown 消毒 -->
              <div class="message-body" v-html="assistantHtml(state.streamingText)"></div>
            </div>

            <section v-if="state.draft" class="draft-result">
              <div class="draft-heading">
                <div>
                  <span>草稿 v{{ state.draft.version }}</span>
                  <h3>{{ state.draft.title }}</h3>
                </div>
                <button type="button" class="draft-apply" @click="applyDraft(state.draft)">
                  应用到编辑器
                </button>
              </div>
              <pre>{{ state.draft.bodyMarkdown }}</pre>
            </section>

            <div v-if="state.error" class="agent-error" role="alert">
              {{ state.error.message }}
            </div>
          </template>
        </main>

        <footer class="agent-composer">
          <textarea v-model="prompt" rows="3" maxlength="8000" placeholder="问我论坛内容相关的问题，或描述发帖需求..."
                    @keydown="submitOnShortcut"></textarea>
          <button v-if="submitting" type="button" class="composer-command run-cancel" @click="cancel">
            <VideoPause/>取消
          </button>
          <button v-else type="button" class="composer-command" :disabled="!canSend" @click="submit()">
            <Promotion/>发送
          </button>
        </footer>
      </aside>
    </transition>
  </div>
</template>

<style scoped>
.agent-launcher {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1200;
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: white;
  background: #2563eb;
  box-shadow: 0 8px 24px rgb(15 23 42 / 24%);
  cursor: pointer;
}

.agent-launcher svg,
.icon-button svg,
.composer-command svg {
  width: 20px;
  height: 20px;
}

.agent-panel {
  position: fixed;
  top: 65px;
  right: 0;
  bottom: 0;
  z-index: 1300;
  width: min(430px, 100vw);
  display: grid;
  grid-template-rows: auto auto 1fr auto;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border-radius: 14px 0 0 0;
  box-shadow: -16px 0 48px rgb(15 23 42 / 14%), 0 0 0 1px rgb(15 23 42 / 4%);
  overflow: hidden;
}

.agent-header,
.session-toolbar,
.agent-composer {
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.agent-header,
.agent-header > div,
.session-toolbar,
.draft-heading,
.composer-command {
  display: flex;
  align-items: center;
}

.agent-header {
  justify-content: space-between;
}

.agent-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.agent-brand__mark {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  background: #2563eb;
  box-shadow: 0 6px 16px rgb(37 99 235 / 22%);
}

.agent-brand__mark svg {
  width: 19px;
  height: 19px;
}

.agent-header h2,
.draft-heading h3 {
  margin: 0;
  font-size: 17px;
  letter-spacing: 0;
}

.agent-brand .run-state {
  display: block;
  margin-left: 0;
  font-size: 12px;
}

.run-state {
  margin-left: 8px;
  font-size: 12px;
  color: #16803c;
}

.session-toolbar {
  gap: 8px;
}

.session-toolbar select {
  min-width: 0;
  height: 34px;
  flex: 1;
  padding: 0 8px;
  color: inherit;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  outline: none;
  transition: border-color .2s ease;
}

.session-toolbar select:hover {
  border-color: var(--el-border-color-hover);
}

.icon-button {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  padding: 0;
  color: var(--el-text-color-regular);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 5px;
  cursor: pointer;
  transition: background .2s ease, color .2s ease;
}

.icon-button:hover { background: var(--el-fill-color-light); }
.icon-button.danger:hover { color: #dc2626; }
.icon-button:disabled { opacity: .4; cursor: not-allowed; }

.agent-content {
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  scrollbar-width: thin;
  scrollbar-color: var(--el-border-color) transparent;
  scroll-behavior: smooth;
}

.agent-content::-webkit-scrollbar {
  width: 6px;
}

.agent-content::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: var(--el-border-color);
}

.agent-content::-webkit-scrollbar-track {
  background: transparent;
}

.center-state,
.empty-state {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.message {
  width: fit-content;
  max-width: 88%;
  margin-bottom: 10px;
  padding: 9px 11px;
  border-radius: 12px;
  overflow-wrap: anywhere;
  font-size: 14px;
  animation: message-rise .22s ease;
}

@keyframes message-rise {
  from {
    opacity: 0;
    transform: translateY(6px);
  }

  to {
    opacity: 1;
    transform: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .message {
    animation: none;
  }
}

.message.user {
  margin-left: auto;
  color: white;
  background: linear-gradient(135deg, #2f72ee, #2563eb);
  border-bottom-right-radius: 4px;
  white-space: pre-wrap;
}

.message.assistant {
  background: var(--el-fill-color-light);
  border: 1px solid color-mix(in srgb, var(--el-border-color) 55%, transparent);
  border-bottom-left-radius: 4px;
}

.message-body :deep(a) {
  color: #2563eb;
}

.message-tools {
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.message-tools .tool-row {
  min-height: 24px;
  font-size: 12px;
}

.message-citations {
  margin: 8px 0 0;
  padding-top: 6px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.message.question { border-left: 3px solid #d97706; }

.streaming .message-body::after {
  content: '';
  width: 7px;
  height: 14px;
  display: inline-block;
  margin-left: 3px;
  vertical-align: -2px;
  border-radius: 2px;
  background: #2563eb;
  animation: caret-blink 1s steps(2, start) infinite;
}

@keyframes caret-blink {
  50% { opacity: 0; }
}

.context-notice {
  margin-bottom: 10px;
}

.context-notice :deep(.el-alert__title) {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.context-notice-text {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}

.context-notice-time {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.tool-timeline {
  margin: 12px 0;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}

.tool-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.tool-row svg { width: 15px; height: 15px; }
.tool-complete { color: #16803c; }

.citation-list {
  display: grid;
  gap: 6px;
  margin: 12px 0;
}

.citation-list a {
  color: #2563eb;
  font-size: 13px;
  text-decoration: none;
}

.citation-list a span { margin-right: 6px; color: var(--el-text-color-secondary); }

.draft-result {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, #f59e0b 32%, transparent);
  border-radius: 12px;
  background: color-mix(in srgb, #f59e0b 7%, var(--el-bg-color));
}

.draft-heading {
  justify-content: space-between;
  gap: 12px;
}

.draft-heading span { color: #a16207; font-size: 12px; }

.draft-apply,
.composer-command {
  border: 0;
  border-radius: 5px;
  cursor: pointer;
}

.draft-apply {
  flex: 0 0 auto;
  padding: 7px 10px;
  color: white;
  background: #a16207;
  border-radius: 8px;
  transition: background .2s ease;
}

.draft-apply:hover { background: #8f5606; }

.draft-result pre {
  max-height: 180px;
  margin: 12px 0 0;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  font-size: 13px;
}

.agent-error {
  margin-top: 12px;
  padding: 10px;
  color: #b91c1c;
  background: color-mix(in srgb, #ef4444 8%, var(--el-bg-color));
  border-left: 3px solid #dc2626;
  border-radius: 6px;
  font-size: 13px;
}

.agent-composer {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 0;
}

.agent-composer textarea {
  min-width: 0;
  resize: none;
  padding: 9px;
  color: inherit;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  font: inherit;
  font-size: 14px;
  outline: none;
  transition: border-color .2s ease, box-shadow .2s ease;
}

.agent-composer textarea:focus {
  border-color: rgba(37, 99, 235, .55);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, .12);
}

.composer-command {
  align-self: end;
  gap: 5px;
  height: 36px;
  padding: 0 12px;
  color: white;
  background: #2563eb;
  border-radius: 10px;
  transition: background .2s ease, opacity .2s ease;
}

.composer-command:hover { background: #1d4fd8; }
.composer-command.run-cancel { background: #b45309; }
.composer-command:disabled { opacity: .45; cursor: not-allowed; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.agent-panel-enter-active,
.agent-panel-leave-active {
  transition: transform .28s cubic-bezier(.32, .72, .35, 1), opacity .28s ease;
}

.agent-panel-enter-from,
.agent-panel-leave-to {
  opacity: 0;
  transform: translateX(60px);
}

@media (max-width: 640px) {
  .agent-panel { top: 0; width: 100vw; border-radius: 0; }
  .agent-launcher { right: 16px; bottom: 16px; }
}
</style>
