import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import {
  editorOptimizationRequest,
  pendingDraftApplication,
  publishDraftApplication,
  resetEditorBridge
} from '@/agent/editorBridge'

const setContents = vi.hoisted(() => vi.fn())

vi.mock('@vueup/vue-quill', () => ({
  Delta: class Delta {
    constructor(value = { ops: [] }) {
      this.ops = value.ops || []
    }
  },
  Quill: { register: vi.fn() },
  QuillEditor: {
    name: 'QuillEditor',
    template: '<div class="quill-editor-stub"></div>',
    methods: {
      setContents,
      getQuill: () => ({ clipboard: { convert: () => ({ ops: [] }) } })
    }
  }
}))
vi.mock('quill-image-resize-vue', () => ({ default: {} }))
vi.mock('quill-image-super-solution-module', () => ({
  ImageExtend: {},
  QuillWatch: { emit: vi.fn() }
}))
vi.mock('@/store', () => ({
  useStore: () => ({
    forum: { types: [{ id: 1, name: '分享', desc: '经验分享', color: '#16803c' }] }
  })
}))
vi.mock('@/net/api/forum', () => ({ apiForumTopicCreate: vi.fn() }))
vi.mock('@/net', () => ({ accessHeader: () => ({ Authorization: 'Bearer token' }) }))

import TopicEditor from './TopicEditor.vue'

enableAutoUnmount(afterEach)

describe('TopicEditor Agent integration', () => {
  beforeEach(() => {
    resetEditorBridge()
    setContents.mockReset()
    sessionStorage.clear()
  })

  test('exposes an AI optimization command in the editor', () => {
    const wrapper = mount(TopicEditor, {
      props: { show: true },
      global: {
        stubs: {
          ElDrawer: { template: '<section><slot name="header"/><slot/></section>' },
          ElSelect: { template: '<div><slot/></div>' },
          ElOption: { template: '<div><slot/></div>' },
          ElInput: { template: '<input />' },
          ElButton: { template: '<button><slot/></button>' },
          ElDialog: { template: '<div><slot/></div>' }
        }
      }
    })

    expect(wrapper.text()).toContain('AI 优化')
  })

  test('keeps editor content and version tracking intact when draft application fails', async () => {
    let quillContent = { ops: [] }
    let writeCount = 0
    setContents.mockImplementation(content => {
      quillContent = content
      writeCount++
      if(writeCount === 1) throw new Error('quill failed after mutation')
    })
    const wrapper = mount(TopicEditor, {
      props: {
        show: true,
        acceptUntargetedDraft: true,
        defaultText: JSON.stringify({ ops: [] })
      },
      global: {
        stubs: {
          ElDrawer: {
            emits: ['open'],
            template: '<section><button class="open-drawer" @click="$emit(\'open\')"></button><slot name="header"/><slot/></section>'
          },
          ElSelect: { template: '<div><slot/></div>' },
          ElOption: { template: '<div><slot/></div>' },
          ElInput: {
            props: ['modelValue'],
            emits: ['update:modelValue', 'input'],
            template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event.target.value)" />'
          },
          ElButton: { template: '<button><slot/></button>' },
          ElDialog: { template: '<div><slot/><slot name="footer"/></div>' }
        }
      }
    })

    await wrapper.get('.open-drawer').trigger('click')
    await wrapper.vm.$nextTick()
    publishDraftApplication({
      title: 'Agent title',
      topicTypeId: 1,
      bodyMarkdown: 'Agent body',
      editorVersion: 0
    })
    await vi.waitFor(() => expect(wrapper.text()).toContain('Agent title'))

    const applyButton = wrapper.findAll('button').find(button => button.text().includes('应用到编辑器'))
    await applyButton.trigger('click')
    await vi.waitFor(() => expect(setContents).toHaveBeenCalledTimes(2))

    expect(wrapper.get('input').element.value).toBe('')
    expect(quillContent.ops).toEqual([])

    await wrapper.get('input').setValue('User title')
    const optimizeButton = wrapper.findAll('button').find(button => button.text().includes('AI 优化'))
    await optimizeButton.trigger('click')

    expect(editorOptimizationRequest.value).toMatchObject({
      editorVersion: 3,
      editorDraft: { title: 'User title' }
    })
  })

  test('waits for a newly opened editor to initialize before consuming an untargeted draft', async () => {
    const wrapper = mount(TopicEditor, {
      props: { show: true, acceptUntargetedDraft: true },
      global: {
        stubs: {
          ElDrawer: {
            emits: ['open'],
            template: '<section><button class="open-drawer" @click="$emit(\'open\')"></button><slot name="header"/><slot/></section>'
          },
          ElSelect: { template: '<div><slot/></div>' },
          ElOption: { template: '<div><slot/></div>' },
          ElInput: { template: '<input />' },
          ElButton: { template: '<button><slot/></button>' },
          ElDialog: { template: '<div><slot/><slot name="footer"/></div>' }
        }
      }
    })

    publishDraftApplication({
      title: 'Fresh draft',
      topicTypeId: 1,
      bodyMarkdown: 'Fresh body',
      editorVersion: 0
    })
    await wrapper.vm.$nextTick()

    expect(pendingDraftApplication.value).not.toBeNull()

    await wrapper.get('.open-drawer').trigger('click')
    await vi.waitFor(() => expect(pendingDraftApplication.value).toBeNull())
    await vi.waitFor(() => expect(wrapper.text()).toContain('Fresh draft'))
  })

  test('keeps editor versions monotonic across drawer reopen', async () => {
    const wrapper = mount(TopicEditor, {
      props: {
        show: true,
        acceptUntargetedDraft: true,
        defaultText: JSON.stringify({ ops: [] })
      },
      global: {
        stubs: {
          ElDrawer: {
            emits: ['open'],
            template: '<section><button class="open-drawer" @click="$emit(\'open\')"></button><slot name="header"/><slot/></section>'
          },
          ElSelect: { template: '<div><slot/></div>' },
          ElOption: { template: '<div><slot/></div>' },
          ElInput: {
            props: ['modelValue'],
            emits: ['update:modelValue', 'input'],
            template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event.target.value)" />'
          },
          ElButton: { template: '<button><slot/></button>' },
          ElDialog: { template: '<div><slot/><slot name="footer"/></div>' }
        }
      }
    })

    await wrapper.get('.open-drawer').trigger('click')
    await wrapper.get('input').setValue('First document')
    const optimizeButton = () => wrapper.findAll('button').find(button => button.text().includes('AI 优化'))
    await optimizeButton().trigger('click')
    const firstVersion = editorOptimizationRequest.value.editorVersion

    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    await wrapper.get('.open-drawer').trigger('click')
    await wrapper.get('input').setValue('Second document')
    await optimizeButton().trigger('click')

    expect(editorOptimizationRequest.value.editorVersion).toBeGreaterThan(firstVersion)
  })

  test('restores drafts to the same stable editor identity after remount', async () => {
    const mountEditor = () => mount(TopicEditor, {
      props: {
        show: true,
        editorKey: 'new-topic',
        defaultText: JSON.stringify({ ops: [] })
      },
      global: {
        stubs: {
          ElDrawer: {
            emits: ['open'],
            template: '<section><button class="open-drawer" @click="$emit(\'open\')"></button><slot name="header"/><slot/></section>'
          },
          ElSelect: { template: '<div><slot/></div>' },
          ElOption: { template: '<div><slot/></div>' },
          ElInput: {
            props: ['modelValue'],
            emits: ['update:modelValue', 'input'],
            template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event.target.value)" />'
          },
          ElButton: { template: '<button><slot/></button>' },
          ElDialog: { template: '<div><slot/><slot name="footer"/></div>' }
        }
      }
    })

    const first = mountEditor()
    await first.get('.open-drawer').trigger('click')
    await first.get('input').setValue('Before remount')
    const firstOptimize = first.findAll('button').find(button => button.text().includes('AI 优化'))
    await firstOptimize.trigger('click')
    const firstRequest = { ...editorOptimizationRequest.value }
    first.unmount()

    const restored = mountEditor()
    await restored.get('.open-drawer').trigger('click')
    await restored.get('input').setValue('After remount')
    const restoredOptimize = restored.findAll('button').find(button => button.text().includes('AI 优化'))
    await restoredOptimize.trigger('click')

    expect(editorOptimizationRequest.value.editorId).toBe(firstRequest.editorId)
    expect(editorOptimizationRequest.value.editorVersion).toBeGreaterThan(firstRequest.editorVersion)
  })
})
