# my-project-frontend

This template should help get you started developing with Vue 3 in Vite.

## Recommended IDE Setup

[VSCode](https://code.visualstudio.com/) + [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur) + [TypeScript Vue Plugin (Volar)](https://marketplace.visualstudio.com/items?itemName=Vue.vscode-typescript-vue-plugin).

## Customize configuration

See [Vite Configuration Reference](https://vitejs.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Compile and Minify for Production

```sh
npm run build
```

### End-to-End Tests

Install the Chromium revision pinned by `@playwright/test` after installing dependencies, then run the desktop suite:

```sh
npm run test:e2e:install
npm run test:e2e
```

CI must run `npm run test:e2e:install` before `npm run test:e2e`. To use an existing Chrome executable locally, opt in explicitly by setting `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`:

```powershell
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH='C:\Program Files\Google\Chrome\Application\chrome.exe'
npm run test:e2e
```
