# AGENTS.md

本文件为 AI 编码代理(以及新加入的开发者)提供本仓库的工作指引。修改代码前请先阅读本文件,遵循其中列出的命令与约定。

## 项目概述

一个前后端分离的论坛系统,并内置基于 Spring AI 的论坛智能体(ReAct Agent,支持检索、发帖草稿等工具调用)。

| 模块 | 技术栈 |
| --- | --- |
| `my-project-backend` | Java 17, Spring Boot 3.5, Spring Security + JWT (java-jwt), MyBatis-Plus, Flyway, Spring AI (DeepSeek / OpenAI + Elasticsearch 向量存储), Redis, RabbitMQ, MinIO, Mail |
| `my-project-frontend` | Vue 3 (Composition API), Vite 4, Element Plus 2.11, Pinia, Vue Router, axios, Quill 富文本编辑器, Vitest + Playwright |

## 仓库结构

```
my-project/
├── my-project-backend/          # Spring Boot 后端
│   ├── pom.xml                  # Maven 配置(含 agent-eval profile)
│   └── src/main/java/com/example/
│       ├── agent/               # 论坛智能体模块(api/core/tool/session/search/index/run/config)
│       ├── controller/          # REST 控制器(admin/ 子包为管理端,exception/ 为全局异常处理)
│       ├── service/impl/        # 业务逻辑(接口 + 实现)
│       ├── mapper/              # MyBatis-Plus Mapper
│       ├── repository/          # Spring Data / Elasticsearch Repository
│       ├── entity/              # 数据库实体(dto / vo / vo/request / vo/response / es)
│       ├── filter/              # Servlet 过滤器(如 JWT 鉴权)
│       ├── listener/            # RabbitMQ 监听器
│       └── config/              # Spring 配置类
│   └── src/main/resources/
│       ├── application.yml / application-dev.yml   # 真实配置(已 gitignore,勿提交)
│       ├── application.yml.example / application-dev.yml.example  # 示例配置模板
│       ├── db/migration/        # Flyway 迁移脚本 V1、V2、V3...
│       └── es/                  # Elasticsearch 索引映射
├── my-project-frontend/         # Vue 3 前端
│   ├── src/
│   │   ├── agent/               # 智能体前端(会话 UI、草稿应用等)
│   │   ├── views/               # 页面(forum / admin / welcome / settings 子目录)
│   │   ├── components/          # 组件(命名风格 XxxYyy.vue)
│   │   ├── net/                 # axios 封装(访问令牌存于 localStorage,见 index.js)
│   │   ├── store/               # Pinia store
│   │   └── router/              # 路由
│   ├── e2e/                     # Playwright 端到端测试
│   └── vite.config.js           # 含 Vitest 配置(jsdom 环境,@ 别名指向 src)
├── prohibited.json              # 敏感词过滤列表(勿提交含敏感词的测试内容变更时绕过此文件)
└── log/                         # 运行日志(已 gitignore)
```

## 环境要求

- JDK 17、Maven(推荐用仓库自带的 `mvnw`)、Node.js(npm)
- Docker:后端集成测试使用 Testcontainers,需要本机 Docker 可用
- 运行依赖服务:MySQL、Redis、RabbitMQ、Elasticsearch、MinIO、SMTP 邮件、DeepSeek/OpenAI API Key

**配置文件约定(重要):**

- `application.yml` 与 `application-dev.yml` 含敏感信息,**已被 gitignore,禁止提交**。
- 首次搭建时从 `.example` 版本复制,并填入本地/私密值:
  ```bash
  cp my-project-backend/src/main/resources/application.yml.example my-project-backend/src/main/resources/application.yml
  cp my-project-backend/src/main/resources/application-dev.yml.example my-project-backend/src/main/resources/application-dev.yml
  ```
- 修改配置结构时,同步更新对应的 `.example` 文件(仓库提交历史中有此先例)。
- 真实 IP、密码、API Key 等一律不写入任何被提交的文件。

## 常用命令

### 后端(`my-project-backend/` 目录下)

```bash
./mvnw spring-boot:run            # 启动后端(Windows 用 mvnw.cmd)
./mvnw test                       # 运行单元/集成测试(Testcontainers 需要 Docker)
./mvnw verify                     # 完整构建 + 测试
./mvnw verify -Pagent-eval        # 运行智能体评估测试(*EvaluationIT.java,由 failsafe 执行)
./mvnw clean package              # 打包
```

### 前端(`my-project-frontend/` 目录下)

```bash
npm install                       # 安装依赖
npm run dev                       # 开发服务器(Vite)
npm run build                     # 生产构建
npm run test                      # Vitest 单元测试(匹配 src/**/*.test.js)
npm run test:e2e:install          # 首次需安装 Playwright 的 Chromium
npm run test:e2e                  # Playwright 端到端测试
```

## 数据库与迁移

- 使用 **Flyway** 管理数据库结构,脚本位于 `src/main/resources/db/migration/`。
- 修改表结构时**新增** `V<N>__描述.sql` 迁移脚本(当前最新为 `V3__add_agent_draft_target_editor.sql`),不要修改已应用的旧脚本。
- Elasticsearch 索引映射在 `src/main/resources/es/`,与 `entity/es/` 下的文档类保持一致。

## 代码约定

### 后端
- 标准分层:Controller 只做参数校验与转发,业务逻辑放 Service,数据访问走 Mapper/Repository。
- 对外返回统一 VO(`entity/vo/request`、`entity/vo/response`),不要直接把数据库实体(`entity/dto` 下的数据对象)暴露给接口。
- 全局异常由 `controller/exception/` 下的处理器统一处理,业务代码中抛出后端自定义异常即可,不要在 Controller 里 try-catch 吞掉。
- 使用 Lombok(`@Data`、`@RequiredArgsConstructor` 等)减少样板代码;Lombok 版本已在 pom 中固定为 1.18.46。
- 认证基于 JWT 过滤器 + Redis 存储,涉及鉴权的改动需同步检查 `filter/` 与 `config/` 中的 Security 配置。
- 敏感词过滤依赖根目录 `prohibited.json`,新增内容审查逻辑时应读取该文件而非硬编码。

### 前端
- Vue 3 `<script setup>` + Composition API;Element Plus 组件通过 unplugin 自动按需导入,无需手动 import。
- 路径别名 `@` 指向 `src/`。
- 网络请求统一走 `src/net/index.js` 的封装(自动携带 Bearer Token、统一错误提示),不要在组件里直接 new axios 实例。
- 渲染富文本/Markdown 前必须经过 `dompurify` 消毒,防止 XSS(仓库有相关修复记录,勿回退)。
- 组件测试文件与被测组件同目录,命名为 `Xxx.test.js`。

## 测试要求

- 后端集成测试基于 Testcontainers(MySQL/RabbitMQ/ES),运行前确认 Docker 已启动;无 Docker 时只跑纯单元测试并在结果中说明。
- 智能体评估类测试命名为 `*EvaluationIT.java`,由 `agent-eval` profile 的 failsafe 插件执行,常规 `mvnw test` 不会运行它们。
- 前端 e2e 测试在 `e2e/` 下,CI 中必须先执行 `npm run test:e2e:install`;本地想复用已有 Chrome 可设置 `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`。
- 提交前至少运行:`./mvnw test`(改了后端)与 `npm run test`(改了前端),涉及其它范围再跑对应 e2e。

## Git 约定

- 提交信息使用 Conventional Commits 风格,与现有历史保持一致:`feat:`、`fix:`、`chore:`、`docs:`、`test:` 等,正文用英文或中文均可,但保持简洁。
- 工作文档统一放在 `docs/` 下,**该目录已被 gitignore,属于分支内工作产物,不要提交回主干**。
- 以下内容永远不要提交:`application.yml`、`application-dev.yml`、`log/`、`*.iml`、`.idea/`、`node_modules/`、`target/`、构建产物(`dist/` 视团队约定)。
- 根目录的 `vite-dev.log`、`vite-dev-error.log` 等本地诊断文件不要提交(历史上有专门 commit 处理此类忽略)。

## 给 AI 代理的额外提示

1. **先读后改**:动手前先定位相关代码(后端看 `controller → service → mapper` 链路;前端看 `views → components → net`),不要凭猜测修改。
2. **最小改动**:遵循现有代码风格(缩进、命名、注释密度),不要顺手大规模重构或重新格式化无关代码。
3. **不要编造配置**:需要密钥、密码、端口等环境信息时,向用户询问或使用 `.example` 中的占位符,绝不虚构。
4. **验证再交付**:完成修改后运行对应的测试命令,并在总结中如实报告测试结果(通过/失败/跳过及原因)。
5. **长任务保持沟通**:改动跨前后端时,说明影响面;涉及数据库结构变更必须走 Flyway 新迁移脚本。
