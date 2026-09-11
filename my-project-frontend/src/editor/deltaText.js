export function deltaToModelText(delta) {
  if (!delta?.ops) return ''
  return delta.ops
    .filter(operation => typeof operation.insert === 'string')
    .map(operation => operation.insert)
    .join('')
}
