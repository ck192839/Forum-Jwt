import { shallowRef } from 'vue'

// 头部组件间的轻量事件桥：UserInfo 头像下拉的「消息列表」
// 通知 IndexView 打开通知弹层（el-popover 未暴露 show，只能受控打开）
export const notificationOpenRequest = shallowRef(0)

export function requestNotificationOpen() {
  notificationOpenRequest.value++
}
