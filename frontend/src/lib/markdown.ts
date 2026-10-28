import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

/**
 * Markdown → 安全 HTML。
 * 1. markdown-it 关闭原始 HTML（html:false），源文中的标签会被转义；
 * 2. 再经 DOMPurify 净化，作为第二道防线（链接协议、属性白名单）。
 * 知识库文档、论坛帖子、AI 回答都走这里，组件里不要直接 v-html 未净化内容。
 */
const md = new MarkdownIt({
  html: false,
  linkify: false,
  typographer: false,
  breaks: false,
})

// 外链统一新窗口打开并切断 opener
const defaultLinkOpen =
  md.renderer.rules.link_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]!
  const href = String(token.attrGet('href') ?? '')
  if (/^https?:\/\//i.test(href)) {
    token.attrSet('target', '_blank')
    token.attrSet('rel', 'noopener noreferrer')
  }
  return defaultLinkOpen(tokens, idx, options, env, self)
}

// 标题加 id，便于引用跳转定位（id 由标题文本生成，DOMPurify 会保留 id 属性）
md.renderer.rules.heading_open = (tokens, idx, options, _env, self) => {
  const inline = tokens[idx + 1]
  if (inline?.type === 'inline') {
    tokens[idx]!.attrSet('id', headingId(inline.content))
  }
  return self.renderToken(tokens, idx, options)
}

export function headingId(text: string): string {
  return 'h-' + text.trim().replace(/\s+/g, '-').replace(/[^\p{L}\p{N}_-]/gu, '').slice(0, 64)
}

const PURIFY_CONFIG = {
  ALLOWED_URI_REGEXP: /^(?:https?:\/\/|mailto:|#|\/(?!\/))/i,
  FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'iframe'],
}

// DOMPurify 默认会清除 <a> 的 target/rel，用 hook 保留外链的这两个属性
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    const href = node.getAttribute('href') ?? ''
    if (/^https?:\/\//i.test(href)) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  }
})

export function sanitize(html: string): string {
  return DOMPurify.sanitize(html, PURIFY_CONFIG) as unknown as string
}

export function renderMarkdown(source: string | null | undefined): string {
  if (!source) return ''
  return sanitize(md.render(source))
}
