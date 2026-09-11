import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiSearchIndexRebuild = vi.hoisted(() => vi.fn())
const apiSearchIndexRebuildStatus = vi.hoisted(() => vi.fn())
const apiForumTopicAllList = vi.hoisted(() => vi.fn())
const confirm = vi.hoisted(() => vi.fn())
const success = vi.hoisted(() => vi.fn())
const warning = vi.hoisted(() => vi.fn())
const error = vi.hoisted(() => vi.fn())

vi.mock('@/net/api/forum', () => ({
  apiSearchIndexRebuild,
  apiSearchIndexRebuildStatus,
  apiForumTopicAllList,
  apiForumTopicDelete: vi.fn(),
  apiForumTopicInvisible: vi.fn(),
  apiForumTopicLocked: vi.fn(),
  apiForumTopicTop: vi.fn(),
  apiTopicChangeType: vi.fn()
}))

vi.mock('@/store', () => ({
  useStore: () => ({ avatarUserUrl: value => value })
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual('element-plus')
  return {
    ...actual,
    ElMessage: { success, warning, error },
    ElMessageBox: { confirm }
  }
})

import ForumTopicAdmin from './ForumTopicAdmin.vue'

const stubs = {
  ElIcon: { template: '<span><slot /></span>' },
  ElInput: { props: ['modelValue'], emits: ['update:modelValue', 'clear'], template: '<input />' },
  ElTable: { template: '<div><slot /></div>' },
  ElTableColumn: { template: '<div />' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElPagination: { template: '<div />' },
  ElSelect: { template: '<div><slot /></div>' },
  ElOption: { template: '<div><slot /></div>' },
  ElAvatar: { template: '<span />' },
  ElLink: { template: '<a><slot /></a>' }
}

const mountPage = () => mount(ForumTopicAdmin, {
  props: { types: [] },
  global: { stubs }
})

describe('ForumTopicAdmin index rebuild', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    apiSearchIndexRebuild.mockReset()
    apiSearchIndexRebuildStatus.mockReset()
    apiForumTopicAllList.mockReset().mockImplementation((_page, _size, _keyword, callback) => callback({ list: [], total: 0, blocked: 0 }))
    confirm.mockReset()
    success.mockReset()
    warning.mockReset()
    error.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('does not start rebuilding when confirmation is cancelled', async () => {
    confirm.mockRejectedValueOnce(new Error('cancelled'))
    const wrapper = mountPage()

    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    await flushPromises()

    expect(apiSearchIndexRebuild).not.toHaveBeenCalled()
  })

  test('polls progress and restores the button after completion', async () => {
    confirm.mockResolvedValueOnce()
    apiSearchIndexRebuild.mockImplementation(callback => callback({ running: true, total: 2, processed: 0, failed: 0 }))
    apiSearchIndexRebuildStatus
      .mockImplementationOnce(callback => callback({ running: true, total: 2, processed: 1, failed: 0 }))
      .mockImplementationOnce(callback => callback({ running: false, total: 2, processed: 2, failed: 0 }))
    const wrapper = mountPage()

    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="rebuild-index"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('0/2')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.text()).toContain('1/2')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(wrapper.get('[data-test="rebuild-index"]').attributes('disabled')).toBeUndefined()
    expect(success).toHaveBeenCalled()
  })

  test('clears polling when the page is unmounted', async () => {
    confirm.mockResolvedValueOnce()
    apiSearchIndexRebuild.mockImplementation(callback => callback({ running: true, total: 1, processed: 0, failed: 0 }))
    const wrapper = mountPage()

    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    await flushPromises()
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(2000)

    expect(apiSearchIndexRebuildStatus).not.toHaveBeenCalled()
  })

  test('restores the button and reports a conflict when a rebuild is already running', async () => {
    confirm.mockResolvedValueOnce()
    apiSearchIndexRebuild.mockImplementation((_success, failure) => failure('已有重建任务正在运行'))
    const wrapper = mountPage()

    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="rebuild-index"]').attributes('disabled')).toBeUndefined()
    expect(error).toHaveBeenCalledWith('已有重建任务正在运行')
  })

  test('reports partial failures when the rebuild completes', async () => {
    confirm.mockResolvedValueOnce()
    apiSearchIndexRebuild.mockImplementation(callback => callback({ running: true, total: 1, processed: 0, failed: 0 }))
    apiSearchIndexRebuildStatus.mockImplementationOnce(callback => callback({ running: false, total: 1, processed: 0, failed: 1 }))
    const wrapper = mountPage()

    await wrapper.get('[data-test="rebuild-index"]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(wrapper.text()).toContain('失败 1')
    expect(warning).toHaveBeenCalledWith('索引重建完成，1 个索引任务失败')
  })
})
