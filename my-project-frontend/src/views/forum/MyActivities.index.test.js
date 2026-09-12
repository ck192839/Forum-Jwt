import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const apiActivityMyOrders = vi.hoisted(() => vi.fn())

vi.mock('@/net/api/activity', () => ({ apiActivityMyOrders }))

import MyActivities from './MyActivities.vue'

const stubs = {
  LightCard: { template: '<div class="light-card"><slot /></div>' },
  ElTag: { template: '<span><slot /></span>' },
  ElEmpty: { props: ['description'], template: '<div>{{ description }}</div>' }
}

const mountPage = () => mount(MyActivities, {
  global: {
    stubs,
    directives: { loading: {} }
  }
})

describe('MyActivities polling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    apiActivityMyOrders.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('shows the empty hint when there is no order', async () => {
    apiActivityMyOrders.mockImplementation(callback => callback([]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('还没有报名记录')
  })

  test('polls every two seconds while an order is processing and stops once settled', async () => {
    apiActivityMyOrders
      .mockImplementationOnce(callback => callback([
        { id: 1, activityId: 1, activityTitle: '校园技术沙龙', status: 0, createTime: '2026-09-12T12:00:00' }
      ]))
      .mockImplementationOnce(callback => callback([
        { id: 1, activityId: 1, activityTitle: '校园技术沙龙', status: 1, createTime: '2026-09-12T12:00:00' }
      ]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('校园技术沙龙')
    expect(wrapper.text()).toContain('排队中')
    expect(wrapper.find('[data-test="polling-hint"]').exists()).toBe(true)

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(wrapper.text()).toContain('报名成功')

    const callsAfterSettled = apiActivityMyOrders.mock.calls.length
    await vi.advanceTimersByTimeAsync(6000)
    await flushPromises()
    expect(apiActivityMyOrders.mock.calls.length).toBe(callsAfterSettled)
  })

  test('stops polling when the page is unmounted', async () => {
    apiActivityMyOrders.mockImplementation(callback => callback([
      { id: 1, activityId: 1, activityTitle: '校园技术沙龙', status: 0, createTime: '2026-09-12T12:00:00' }
    ]))
    const wrapper = mountPage()
    await flushPromises()

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(6000)

    expect(apiActivityMyOrders.mock.calls.length).toBe(1)
  })
})
