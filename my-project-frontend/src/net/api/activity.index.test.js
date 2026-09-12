import { describe, expect, test, vi } from 'vitest'

const get = vi.hoisted(() => vi.fn())
const post = vi.hoisted(() => vi.fn())

vi.mock('@/net', () => ({ get, post }))

import {
  apiActivityGrab,
  apiActivityList,
  apiActivityMyOrders,
  apiAdminActivityList,
  apiAdminActivitySave,
  apiAdminActivityDelete,
  apiAdminActivitySetStatus
} from './activity'

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

describe('activity admin API', () => {
  test('admin list hits the admin endpoint with paging and keyword', () => {
    const success = vi.fn()
    apiAdminActivityList(2, 10, '沙龙', success)
    expect(get).toHaveBeenCalledWith('/api/admin/activity/list?page=2&size=10&keyword=沙龙', success)

    apiAdminActivityList(1, 10, undefined, success)
    expect(get).toHaveBeenCalledWith('/api/admin/activity/list?page=1&size=10', success)
  })

  test('admin save posts the activity payload', () => {
    const success = vi.fn()
    const failure = vi.fn()
    apiAdminActivitySave({ id: null, title: 'x' }, success, failure)
    expect(post).toHaveBeenCalledWith('/api/admin/activity/save', { id: null, title: 'x' }, success, failure)
  })

  test('admin delete and status hit their endpoints', () => {
    const success = vi.fn()
    apiAdminActivityDelete(3, success)
    expect(get).toHaveBeenCalledWith('/api/admin/activity/delete?id=3', success, undefined)

    apiAdminActivitySetStatus(3, 0, success)
    expect(post).toHaveBeenCalledWith('/api/admin/activity/status', { id: 3, status: 0 }, success, undefined)
  })
})
