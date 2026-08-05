import { expect, test } from '@playwright/test'

const requirement = '帮我写一篇新生校园网使用指南，先检查论坛里是否有重复内容'
const requestSettleMs = 500
const draft = {
  title: '新生校园网使用指南',
  topicTypeId: 7,
  bodyMarkdown: '连接 **Campus-WiFi** 后登录认证页面。\n\n遇到问题请先检查账号状态。',
  citations: [{ topicId: 42, title: '校园网常见问题' }],
  draftVersion: 1,
  basedOnEditorVersion: 0,
  targetEditorId: null
}

test('Agent drafts with duplicate-check evidence and waits for the user to publish', async ({ page }) => {
  const topicCreateRequests = []
  const { unexpectedApiRequests } = await installApiFixture(page, topicCreateRequests)
  await authenticate(page)

  await page.goto('/index')
  await page.getByRole('button', { name: '打开发帖助手' }).click()
  const assistant = page.getByRole('complementary', { name: '发帖助手' })
  await expect(assistant).toBeVisible()

  await assistant.getByPlaceholder('描述发帖需求...').fill(requirement)
  await assistant.getByRole('button', { name: '发送' }).click()

  await expect(assistant.getByText(requirement, { exact: true })).toBeVisible()
  const tools = assistant.getByRole('region', { name: '工具执行状态' })
  await expect(tools.getByRole('status', { name: '查找相似帖子：已完成' })).toBeVisible()
  await expect(tools.getByRole('status', { name: '阅读公开帖子：已完成' })).toBeVisible()
  await expect(assistant.getByRole('navigation', { name: '引用帖子' })
    .getByRole('link', { name: '#42校园网常见问题' })).toBeVisible()
  await expect(assistant.getByRole('heading', { name: draft.title })).toBeVisible()
  expect(topicCreateRequests).toHaveLength(0)

  await assistant.getByRole('button', { name: '应用到编辑器' }).click()

  const editor = page.getByRole('dialog', { name: '发布帖子编辑器' })
  await expect(editor).toBeVisible()
  const preview = page.getByRole('dialog', { name: '应用 Agent 草稿' })
  await expect(preview).toBeVisible()
  await expect(preview.getByText('标题', { exact: true })).toBeVisible()
  await expect(preview.getByText(draft.title, { exact: true })).toBeVisible()
  await expect(preview.getByText('板块', { exact: true })).toBeVisible()
  await expect(preview.getByText('校园生活', { exact: true })).toBeVisible()
  await expect(preview.getByRole('region', { name: '正文差异' })).toContainText('Campus-WiFi')
  expect(topicCreateRequests).toHaveLength(0)

  await preview.getByRole('button', { name: '应用到编辑器' }).click()
  await expect(preview).toBeHidden()
  await expect(editor.getByRole('textbox', { name: '帖子标题' })).toHaveValue(draft.title)
  await expect(editor.getByRole('combobox', { name: '帖子板块' })).toBeVisible()
  await expect(editor.getByText('校园生活', { exact: true })).toBeVisible()
  const body = editor.getByRole('textbox', { name: '帖子正文' })
  await expect(body).toHaveAttribute('aria-multiline', 'true')
  await expect(body).toContainText('Campus-WiFi')

  await page.waitForTimeout(requestSettleMs)
  expect(topicCreateRequests).toHaveLength(0)
  expect(unexpectedApiRequests).toEqual([])

  const [publishedRequest] = await Promise.all([
    page.waitForRequest(request =>
      request.method() === 'POST' && new URL(request.url()).pathname === '/api/forum/create-topic'
    ),
    editor.getByRole('button', { name: '立即发表主题' }).click()
  ])
  expect(publishedRequest.postDataJSON()).toMatchObject({
    type: draft.topicTypeId,
    title: draft.title
  })
  expect(JSON.stringify(publishedRequest.postDataJSON().content)).toContain('Campus-WiFi')

  await page.waitForTimeout(requestSettleMs)
  expect(topicCreateRequests).toHaveLength(1)
  expect(unexpectedApiRequests).toEqual([])
})

test('API fixture fails closed for unknown routes', async ({ page }) => {
  const topicCreateRequests = []
  const { unexpectedApiRequests } = await installApiFixture(page, topicCreateRequests)
  await authenticate(page)
  await page.goto('/index')

  const result = await page.evaluate(async () => {
    try {
      const response = await fetch('http://localhost:8080/api/unexpected')
      return { resolved: true, status: response.status }
    } catch {
      return { resolved: false }
    }
  })

  expect(result).toEqual({ resolved: false })
  expect(unexpectedApiRequests).toEqual([{ method: 'GET', path: '/api/unexpected' }])
  expect(topicCreateRequests).toHaveLength(0)
})

async function authenticate(page) {
  await page.addInitScript(() => {
    sessionStorage.setItem('authorize', JSON.stringify({
      token: 'playwright-user-token',
      expire: Date.now() + 60 * 60 * 1000,
      role: 'user'
    }))
  })
}

async function installApiFixture(page, topicCreateRequests) {
  let sessionCreated = false
  const unexpectedApiRequests = []

  await page.route('http://localhost:8080/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (path === '/api/agent/sessions/recent') {
      return json(route, sessionCreated
        ? [{ id: 101, status: 'ACTIVE', updatedAt: '2026-08-05T08:00:00Z' }]
        : [])
    }
    if (path === '/api/agent/sessions' && request.method() === 'POST') {
      sessionCreated = true
      return json(route, { id: 101, status: 'ACTIVE' })
    }
    if (path === '/api/agent/sessions/101' && request.method() === 'GET') {
      return json(route, {
        session: { id: 101, status: 'ACTIVE' },
        messages: [],
        events: [],
        draft: null
      })
    }
    if (path === '/api/agent/sessions/101/runs' && request.method() === 'POST') {
      const submitted = request.postDataJSON()
      expect(submitted).toMatchObject({ message: requirement, editorVersion: 0 })
      expect(topicCreateRequests).toHaveLength(0)
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream; charset=utf-8',
        body: agentEventStream()
      })
    }
    if (path === '/api/forum/create-topic' && request.method() === 'POST') {
      topicCreateRequests.push(request.postDataJSON())
      return json(route, null)
    }

    const fixtures = {
      '/api/user/info': {
        id: 9,
        username: 'playwright-user',
        email: 'playwright@example.test',
        role: 'user',
        avatar: null,
        registerTime: '2026-08-01T00:00:00Z'
      },
      '/api/notification/list': [],
      '/api/forum/types': [{ id: 7, name: '校园生活', color: '#16803c', desc: '校园经验与生活服务' }],
      '/api/forum/list-topic': [],
      '/api/forum/top-topic': [],
      '/api/forum/weather': { location: {}, now: {}, hourly: [] }
    }
    if (request.method() === 'GET' && Object.hasOwn(fixtures, path)) {
      return json(route, fixtures[path])
    }

    unexpectedApiRequests.push({ method: request.method(), path })
    return route.abort('failed')
  })

  return { unexpectedApiRequests }
}

function agentEventStream() {
  const events = [
    ['run_started', { runId: 'run-e2e-1', sessionId: 101 }],
    ['message_delta', { text: '已检查历史帖子并整理出一份草稿。' }],
    ['tool_started', { runId: 'run-e2e-1', toolName: 'search_similar_topics' }],
    ['tool_completed', { runId: 'run-e2e-1', toolName: 'search_similar_topics' }],
    ['tool_started', { runId: 'run-e2e-1', toolName: 'read_public_topic' }],
    ['tool_completed', { runId: 'run-e2e-1', toolName: 'read_public_topic' }],
    ['citation', { topicId: 42, title: '校园网常见问题' }],
    ['draft_ready', draft],
    ['run_completed', { runId: 'run-e2e-1', status: 'COMPLETED' }]
  ]
  return events.map(([type, payload]) => `event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`).join('')
}

function json(route, data) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data })
  })
}
