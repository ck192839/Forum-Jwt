import { nextTick, reactive, ref } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const createController = vi.hoisted(() => vi.fn())
vi.mock('./useAgentAssistant', () => ({
  createAgentAssistantController: createController
}))

const routerPush = vi.hoisted(() => vi.fn())
vi.mock('@/router', () => ({
  default: {
    push: routerPush,
    currentRoute: { value: { path: '/' } }
  }
}))

import AgentAssistant from './AgentAssistant.vue'
import {
  pendingDraftApplication,
  requestEditorOptimization,
  resetEditorBridge
} from './editorBridge'

const mountOptions = {
  global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } }
}

describe('AgentAssistant', () => {
  let controller

  beforeEach(() => {
    resetEditorBridge()
    controller = {
      state: reactive({
        sessionId: 7,
        messages: [{ role: 'USER', content: '帮我写校园网教程' }],
        runStatus: 'running',
        streamingText: '正在查找历史帖子',
        timeline: [{ runId: 'r1', toolName: 'search_similar_topics', status: 'completed' }],
        citations: [{ topicId: 12, title: '校园网指南' }],
        question: '适用于哪个宿舍楼？',
        draft: {
          title: '宿舍网络配置指南',
          topicTypeId: 1,
          bodyMarkdown: '正文',
          version: 2,
          editorVersion: 4,
          citations: []
        },
        error: null
      }),
      sessions: ref([{ id: 7, status: 'ACTIVE', updatedAt: '2026-08-05T04:00:00Z' }]),
      prompt: ref(''),
      loading: ref(false),
      submitting: ref(true),
      initialize: vi.fn().mockResolvedValue(undefined),
      selectSession: vi.fn(),
      newSession: vi.fn(),
      deleteCurrentSession: vi.fn(),
      submit: vi.fn(),
      cancel: vi.fn()
    }
    createController.mockReturnValue(controller)
  })

  test('opens with restored public state and emits the selected draft', async () => {
    const wrapper = mount(AgentAssistant, mountOptions)

    await wrapper.get('.agent-launcher').trigger('click')

    expect(controller.initialize).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('查找相似帖子')
    expect(wrapper.text()).toContain('校园网指南')
    expect(wrapper.text()).toContain('宿舍网络配置指南')

    await wrapper.get('.draft-apply').trigger('click')
    expect(wrapper.emitted('apply-draft')[0]).toEqual([controller.state.draft])
  })

  test('cancels the active run from the composer', async () => {
    const wrapper = mount(AgentAssistant, mountOptions)
    await wrapper.get('.agent-launcher').trigger('click')

    await wrapper.get('.run-cancel').trigger('click')

    expect(controller.cancel).toHaveBeenCalledOnce()
  })

  test('runs editor optimization and routes the draft back to its source editor', async () => {
    const wrapper = mount(AgentAssistant, mountOptions)
    const editorDraft = { title: 'Title', topicTypeId: 1, bodyMarkdown: 'Body' }

    requestEditorOptimization({ editorId: 'editor-a', editorVersion: 4, editorDraft })
    await nextTick()
    await nextTick()

    expect(controller.initialize).toHaveBeenCalledOnce()
    await vi.waitFor(() => {
      expect(controller.submit).toHaveBeenCalledWith({
        editorId: 'editor-a',
        editorVersion: 4,
        editorDraft
      })
    })

    controller.state.draft.targetEditorId = 'editor-a'
    await wrapper.get('.draft-apply').trigger('click')
    expect(pendingDraftApplication.value).toMatchObject({
      targetEditorId: 'editor-a',
      draft: controller.state.draft
    })
  })

  test('renders assistant messages as sanitized markdown', async () => {
    controller.state.messages.push({
      role: 'ASSISTANT',
      content: '推荐 **东镇大街** 的牛腩\n\n<script>alert(1)</script>'
    })
    const wrapper = mount(AgentAssistant, mountOptions)
    await wrapper.get('.agent-launcher').trigger('click')

    const body = wrapper.get('.message.assistant .message-body')
    expect(body.html()).toContain('<strong>东镇大街</strong>')
    expect(body.html()).not.toContain('<script')
  })

  test('renders per-message tool activity and citations', async () => {
    controller.state.messages.push({
      role: 'ASSISTANT',
      content: '东镇大街的牛腩口感很好。',
      timeline: [{ runId: 'r1', toolName: 'search_similar_topics', status: 'completed' }],
      citations: [{ topicId: 42, title: '牛腩探店' }]
    })
    const wrapper = mount(AgentAssistant, mountOptions)
    await wrapper.get('.agent-launcher').trigger('click')

    const message = wrapper.get('.message.assistant')
    expect(message.find('.message-tools').text()).toContain('查找相似帖子')
    expect(message.get('.message-citations').text()).toContain('牛腩探店')
  })

  test('routes internal markdown links through the router', async () => {
    controller.state.messages.push({
      role: 'ASSISTANT',
      content: '参考 [牛腩探店](/index/topic-detail/42)'
    })
    const wrapper = mount(AgentAssistant, mountOptions)
    await wrapper.get('.agent-launcher').trigger('click')

    await wrapper.get('.message.assistant .message-body a').trigger('click')

    expect(routerPush).toHaveBeenCalledWith('/index/topic-detail/42')
  })
})
