import { describe, expect, test, vi } from 'vitest'

const get = vi.hoisted(() => vi.fn())
const post = vi.hoisted(() => vi.fn())

vi.mock('@/net', () => ({ get, post }))

import { apiActivityGrab, apiActivityList, apiActivityMyOrders } from './activity'

describe('activity API', () => {
  test('list hits the activity list endpoint', () => {
    const success = vi.fn()
    apiActivityList(success)
    expect(get).toHaveBeenCalledWith('/api/activity/list', success)
  })

  test('grab posts the activity id with a failure callback', () => {
    const success = vi.fn()
    const failure = vi.fn()
    apiActivityGrab({ activityId: 1 }, success, failure)
    expect(post).toHaveBeenCalledWith('/api/activity/grab', { activityId: 1 }, success, failure)
  })

  test('my orders hits the my-orders endpoint', () => {
    const success = vi.fn()
    apiActivityMyOrders(success)
    expect(get).toHaveBeenCalledWith('/api/activity/my-orders', success)
  })
})
