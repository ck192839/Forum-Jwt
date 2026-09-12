import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiAdminActivityList = vi.hoisted(() => vi.fn())
const apiAdminActivitySave = vi.hoisted(() => vi.fn())
const apiAdminActivityDelete = vi.hoisted(() => vi.fn())
const apiAdminActivitySetStatus = vi.hoisted(() => vi.fn())
const confirm = vi.hoisted(() => vi.fn())
const success = vi.hoisted(() => vi.fn())
const warning = vi.hoisted(() => vi.fn())

vi.mock('@/net/api/activity', () => ({
  apiAdminActivityList,
  apiAdminActivitySave,
  apiAdminActivityDelete,
  apiAdminActivitySetStatus
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual('element-plus')
  return {
    ...actual,
    ElMessage: { success, warning, error: vi.fn() },
    ElMessageBox: { confirm }
  }
})

import ActivityAdmin from './ActivityAdmin.vue'

// jsdom 没有 ResizeObserver，真实 el-table 需要它
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
global.ResizeObserver = global.ResizeObserver || ResizeObserverStub

const stubs = {
  ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input />' },
  ElButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' },
  ElPagination: { template: '<div />' },
  ElDialog: { props: ['modelValue', 'title'], template: '<div v-if="modelValue"><slot name="footer" /><slot /></div>' },
  ElDatePicker: { props: ['modelValue'], template: '<input />' },
  ElInputNumber: { props: ['modelValue'], template: '<input />' }
}

const row = {
  id: 1,
  title: '校园技术沙龙',
  description: '并发主题分享',
  location: '活动中心 302',
  activityTime: 1789000000000,
  totalStock: 20,
  grabbed: 3,
  grabStartTime: 1788300000000,
  grabEndTime: 1788900000000,
  status: 1
}

const mountPage = () => mount(ActivityAdmin, { global: { stubs } })

describe('ActivityAdmin', () => {
  beforeEach(() => {
    apiAdminActivityList.mockReset().mockImplementation((_p, _s, _k, callback) =>
      callback({ list: [row], total: 1 }))
    apiAdminActivitySave.mockReset()
    apiAdminActivityDelete.mockReset()
    apiAdminActivitySetStatus.mockReset()
    confirm.mockReset()
    success.mockReset()
    warning.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('loads and renders the activity list', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(apiAdminActivityList).toHaveBeenCalledWith(1, 10, undefined, expect.any(Function))
    expect(wrapper.text()).toContain('校园技术沙龙')
    expect(wrapper.text()).toContain('3 / 20')
    expect(wrapper.get('[data-test="status-tag"]').text()).toBe('上架')
  })

  test('blocks saving an incomplete form with a validation hint', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-test="create-button"]').trigger('click')
    await wrapper.get('[data-test="save-button"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith('请完整填写活动信息')
    expect(apiAdminActivitySave).not.toHaveBeenCalled()
  })

  test('saves an edited activity through the dialog', async () => {
    apiAdminActivitySave.mockImplementation((_data, callback) => callback())
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-test="edit-button"]').trigger('click')
    await wrapper.get('[data-test="save-button"]').trigger('click')
    await flushPromises()

    expect(apiAdminActivitySave).toHaveBeenCalledWith(
      expect.objectContaining({ id: 1, title: '校园技术沙龙', totalStock: 20 }),
      expect.any(Function), expect.any(Function)
    )
    expect(success).toHaveBeenCalledWith('保存成功')
  })

  test('toggles the activity status', async () => {
    apiAdminActivitySetStatus.mockImplementation((_id, _status, callback) => callback())
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-test="toggle-button"]').trigger('click')
    await flushPromises()

    expect(apiAdminActivitySetStatus).toHaveBeenCalledWith(1, 0, expect.any(Function), expect.any(Function))
    expect(success).toHaveBeenCalledWith('已下架')
  })

  test('deletes only after confirmation', async () => {
    confirm.mockRejectedValueOnce(new Error('cancelled'))
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-test="delete-button"]').trigger('click')
    await flushPromises()
    expect(apiAdminActivityDelete).not.toHaveBeenCalled()

    confirm.mockResolvedValueOnce()
    apiAdminActivityDelete.mockImplementation((_id, callback) => callback())
    await wrapper.get('[data-test="delete-button"]').trigger('click')
    await flushPromises()
    expect(apiAdminActivityDelete).toHaveBeenCalledWith(1, expect.any(Function), expect.any(Function))
    expect(success).toHaveBeenCalledWith('已删除')
  })
})
