import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiActivityList = vi.hoisted(() => vi.fn())
const apiActivityGrab = vi.hoisted(() => vi.fn())
const success = vi.hoisted(() => vi.fn())
const warning = vi.hoisted(() => vi.fn())
const routerPush = vi.hoisted(() => vi.fn())

vi.mock('@/net/api/activity', () => ({ apiActivityList, apiActivityGrab }))

vi.mock('@/router', () => ({ default: { push: routerPush } }))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual('element-plus')
  return {
    ...actual,
    ElMessage: { success, warning, error: vi.fn() }
  }
})

import ActivityList from './ActivityList.vue'

const stubs = {
  LightCard: { template: '<div class="light-card"><slot /></div>' },
  ElIcon: { template: '<span><slot /></span>' },
  ElTag: { template: '<span><slot /></span>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' }
}

// 固定“当前时间”，保证报名窗口判断可复现
const NOW = new Date('2026-09-12T12:00:00')

const openActivity = {
  id: 1,
  title: '校园技术沙龙',
  description: '并发主题分享',
  location: '活动中心 302',
  activityTime: new Date(NOW.getTime() + 7 * 86400_000).toISOString(),
  totalStock: 20,
  grabbed: 3,
  grabStartTime: new Date(NOW.getTime() - 86400_000).toISOString(),
  grabEndTime: new Date(NOW.getTime() + 6 * 86400_000).toISOString()
}

const mountPage = () => mount(ActivityList, {
  global: {
    stubs,
    directives: { loading: {} }
  }
})

describe('ActivityList', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
    apiActivityList.mockReset()
    apiActivityGrab.mockReset()
    routerPush.mockReset()
    success.mockReset()
    warning.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('shows the empty hint when no activity exists', async () => {
    apiActivityList.mockImplementation(callback => callback([]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('暂时没有可报名的活动')
  })

  test('renders an active activity with remaining quota and a clickable grab button', async () => {
    apiActivityList.mockImplementation(callback => callback([openActivity]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('校园技术沙龙')
    expect(wrapper.text()).toContain('剩余 17 / 20')
    expect(wrapper.get('[data-test="grab-button"]').attributes('disabled')).toBeUndefined()
  })

  test('disables the button when the quota is sold out', async () => {
    apiActivityList.mockImplementation(callback => callback([{ ...openActivity, grabbed: 20 }]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('已抢完')
    expect(wrapper.get('[data-test="grab-button"]').attributes('disabled')).toBeDefined()
  })

  test('disables the button before the grab window opens', async () => {
    apiActivityList.mockImplementation(callback => callback([{
      ...openActivity,
      grabStartTime: new Date(NOW.getTime() + 3600_000).toISOString()
    }]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.get('[data-test="grab-button"]').attributes('disabled')).toBeDefined()
  })

  test('grab navigates to my activities with the backend hint on success', async () => {
    apiActivityList.mockImplementation(callback => callback([openActivity]))
    apiActivityGrab.mockImplementation((_data, callback) => callback('已提交，报名处理中'))
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-test="grab-button"]').trigger('click')
    await flushPromises()

    expect(apiActivityGrab).toHaveBeenCalledWith({ activityId: 1 }, expect.any(Function), expect.any(Function))
    expect(success).toHaveBeenCalledWith('已提交，报名处理中')
    expect(routerPush).toHaveBeenCalledWith('/index/my-activities')
  })

  test('grab shows a warning without navigating on failure', async () => {
    apiActivityList.mockImplementation(callback => callback([openActivity]))
    apiActivityGrab.mockImplementation((_data, _success, failure) => failure('名额已抢完'))
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.get('[data-test="grab-button"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith('名额已抢完')
    expect(routerPush).not.toHaveBeenCalled()
  })
})
