import { sanitize } from './markdown'

/**
 * 把已净化 HTML 里的 [n] 标记替换为可交互的引用角标。
 * 只替换 n 属于 validRanks 的标记（对应引用的 rankNo），其它方括号原样保留。
 * 跳过 code/pre/a 内部的文本，避免改写代码示例或链接文字。
 * 输出再过一遍 DOMPurify，保证结果仍然安全。
 */
const MARK = /\[(\d{1,2})\]/g
const SKIP = new Set(['CODE', 'PRE', 'A'])

export function linkCitations(safeHtml: string, validRanks: ReadonlySet<number>, idPrefix: string): string {
  if (!safeHtml || validRanks.size === 0) return safeHtml
  const tpl = document.createElement('template')
  tpl.innerHTML = safeHtml

  const walker = document.createTreeWalker(tpl.content, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      for (let p = node.parentElement; p; p = p.parentElement) {
        if (SKIP.has(p.tagName)) return NodeFilter.FILTER_REJECT
      }
      MARK.lastIndex = 0
      return MARK.test(node.nodeValue ?? '') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP
    },
  })

  const targets: Text[] = []
  for (let n = walker.nextNode(); n; n = walker.nextNode()) targets.push(n as Text)

  for (const textNode of targets) {
    const text = textNode.nodeValue ?? ''
    const frag = document.createDocumentFragment()
    let last = 0
    MARK.lastIndex = 0
    for (let m = MARK.exec(text); m; m = MARK.exec(text)) {
      const rank = Number(m[1])
      if (!validRanks.has(rank)) continue
      if (m.index > last) frag.append(text.slice(last, m.index))
      const sup = document.createElement('sup')
      sup.className = 'cite-mark'
      const a = document.createElement('a')
      a.href = `#${idPrefix}-${rank}`
      a.setAttribute('data-rank', String(rank))
      a.setAttribute('aria-label', `引用 ${rank}`)
      a.textContent = String(rank)
      sup.append(a)
      frag.append(sup)
      last = m.index + m[0].length
    }
    if (last === 0) continue
    if (last < text.length) frag.append(text.slice(last))
    textNode.replaceWith(frag)
  }
  return sanitize(tpl.innerHTML)
}

/** 回答正文里实际出现过的引用序号（用于给未被正文提及的引用降权显示）。 */
export function mentionedRanks(answer: string): Set<number> {
  const out = new Set<number>()
  for (const m of answer.matchAll(MARK)) out.add(Number(m[1]))
  return out
}
