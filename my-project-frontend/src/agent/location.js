// 用户定位共享模块：浏览器 geolocation 定位一次并缓存成功结果。
// TopicList 天气展示与 Agent 问答（天气上下文）共用，避免重复弹定位权限。
// 定位失败不缓存（下次可重试），调用方自行决定兜底行为（后端对缺省坐标也有默认值）。

export const DEFAULT_LOCATION = { longitude: 116.40529, latitude: 39.90499 }

let cachedLocation = null
let pendingRequest = null

/**
 * 请求用户定位。
 * @param onError 定位失败回调（GeolocationPositionError），仅首次失败的请求会触发
 * @returns 成功时解析为 { longitude, latitude }，失败时解析为 null
 */
export function requestUserLocation(onError) {
  if (cachedLocation) return Promise.resolve(cachedLocation)
  if (!pendingRequest) {
    // 注意：不能在 executor 里同步置空 pendingRequest（会被随后的赋值覆盖），
    // 因此用 finally 在结算后异步清空，失败时下次调用可重试。
    pendingRequest = new Promise(resolve => {
      if (!navigator.geolocation?.getCurrentPosition) {
        resolve(null)
        return
      }
      navigator.geolocation.getCurrentPosition(
        position => {
          cachedLocation = { longitude: position.coords.longitude, latitude: position.coords.latitude }
          resolve(cachedLocation)
        },
        error => {
          if (onError) onError(error)
          resolve(null)
        },
        { timeout: 10000, enableHighAccuracy: false }
      )
    }).finally(() => {
      pendingRequest = null
    })
  }
  return pendingRequest
}

/** 定位失败时回退默认坐标，保证调用方总能拿到一组可用坐标。 */
export async function getUserLocationWithFallback(onError) {
  return (await requestUserLocation(onError)) || DEFAULT_LOCATION
}

/** 仅供测试：清空成功定位的缓存。 */
export function clearLocationCacheForTests() {
  cachedLocation = null
  pendingRequest = null
}
