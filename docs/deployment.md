# Forum Agent Deployment Guide

## Runtime Topology

The feature stays inside the existing Spring Boot monolith and Vue 3 client.
It requires the forum's existing MySQL, Redis, RabbitMQ, Elasticsearch, MinIO,
mail, and weather integrations. DeepSeek supplies chat/tool calling and Bailian
supplies `text-embedding-v4` embeddings.

Recommended versions for this branch:

- Java 17 target and Maven Wrapper from `my-project-backend`
- Node.js 18 or newer and npm
- MySQL 8, Redis 7, RabbitMQ 3, Elasticsearch 8.18.1
- A current Chromium or Chrome for desktop end-to-end tests

## Configuration

Create the ignored runtime configuration from the checked-in example:

```powershell
Copy-Item my-project-backend/src/main/resources/application.yml.example `
  my-project-backend/src/main/resources/application.yml
```

Set secrets in the service environment. Never put their values in YAML, Maven
arguments, shell history checked into the repository, or frontend variables.

```powershell
$env:DB_PASSWORD='...'
$env:RABBIT_PASSWORD='...'
$env:ES_PASSWORD='...'
$env:MINIO_PASSWORD='...'
$env:JWT_KEY='...'
$env:MAIL_USERNAME='...'
$env:MAIL_PASSWORD='...'
$env:WEATHER_KEY='...'
$env:DEEPSEEK_API_KEY='...'
$env:DASHSCOPE_API_KEY='...'
```

Optional model overrides are `DEEPSEEK_BASE_URL`, `DEEPSEEK_CHAT_MODEL`,
`DASHSCOPE_BASE_URL`, and `DASHSCOPE_EMBEDDING_MODEL`. Production Agent limits
bind from `AGENT_EXECUTION_MAX_TOOL_CALLS` and `AGENT_EXECUTION_TIMEOUT`; keep
them at or below eight calls and 60 seconds for the accepted behavior.

The configured MySQL database must already contain the original forum schema.
Flyway runs in baseline mode and creates only the Agent tables. It does not
recreate or migrate existing forum tables.

## Start

Start infrastructure first, then launch the backend and frontend in separate
terminals:

```powershell
cd my-project-backend
.\mvnw.cmd spring-boot:run
```

```powershell
cd my-project-frontend
npm.cmd install
npm.cmd run dev
```

The backend uses `http://localhost:8080`. Vite normally serves the desktop UI
at `http://localhost:5173`; the current frontend API base URL is configured in
`src/main.js`. For a production domain, serve the built assets behind the same
trusted reverse proxy and update that API base URL before building.

After first deployment, an administrator should call
`POST /api/admin/agent/index/rebuild`, then poll
`GET /api/admin/agent/index/status` until rebuilding finishes with no failures.
Normal topic create, update, hide, and delete operations enqueue incremental
index changes after their database transactions commit.

## Verification And Operations

```powershell
cd my-project-backend
.\mvnw.cmd test
.\mvnw.cmd -Pagent-eval verify

cd ..\my-project-frontend
npm.cmd run test
npm.cmd run build
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH='C:\Program Files\Google\Chrome\Application\chrome.exe'
npm.cmd run test:e2e
```

The real evaluation uses paid APIs and requires Docker. Its Markdown and JSON
reports are written only to `my-project-backend/target/agent-evaluation/`.

Operational checks:

- Alert on the topic-index dead-letter queue and rebuild failure count.
- Monitor Agent timeout/error events and DeepSeek token usage.
- Keep RabbitMQ publisher confirms enabled so committed topic changes are not
  silently lost.
- Rotate all model and infrastructure credentials outside the repository.
- Retain the scheduled 30-day Agent session cleanup and the per-user ten-session
  limit.

The Agent has no publishing tool. A generated draft must be applied to the
editor and published through the forum's existing authenticated endpoint.
