import { beforeEach, describe, expect, test, vi } from 'vitest'

const get = vi.hoisted(() => vi.fn())
const post = vi.hoisted(() => vi.fn())

vi.mock('@/net', () => ({ get, post }))

import {
  apiAgentIndexRebuild,
  apiAgentIndexRebuildStatus
} from './forum'

describe('agent index API', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
  })

  test('starts a full index rebuild with POST', () => {
    const success = vi.fn()

    apiAgentIndexRebuild(success)

    expect(post).toHaveBeenCalledWith('/api/admin/agent/index/rebuild', null, success)
  })

  test('loads index rebuild status with GET', () => {
    const success = vi.fn()

    apiAgentIndexRebuildStatus(success)

    expect(get).toHaveBeenCalledWith('/api/admin/agent/index/status', success)
  })

  test('forwards failure and network error callbacks', () => {
    const success = vi.fn()
    const failure = vi.fn()
    const error = vi.fn()

    apiAgentIndexRebuild(success, failure, error)
    apiAgentIndexRebuildStatus(success, failure, error)

    expect(post).toHaveBeenCalledWith('/api/admin/agent/index/rebuild', null, success, failure, error)
    expect(get).toHaveBeenCalledWith('/api/admin/agent/index/status', success, failure, error)
  })
})
