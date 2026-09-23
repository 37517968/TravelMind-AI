// 极简且安全的 Markdown 子集渲染：先转义 HTML，再只生成白名单标签，避免模型输出造成 XSS。
// 覆盖旅行方案常见语法：标题、粗斜体、行内代码、代码块、有序/无序列表、表格、引用、分隔线、链接、换行。

const escapeHtml = (value) => String(value)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')

const safeUrl = (url) => {
  const value = String(url).trim()
  return /^(https?:\/\/|mailto:|#|\/)/i.test(value) ? value : '#'
}

const inline = (text) => escapeHtml(text)
  .replace(/`([^`]+)`/g, '<code>$1</code>')
  .replace(/!?\[([^\]]*)\]\(([^)\s]+)[^)]*\)/g, (match, label, url) =>
    `<a href="${safeUrl(url)}" target="_blank" rel="noopener noreferrer">${label || safeUrl(url)}</a>`)
  .replace(/\*\*\*([^*]+)\*\*\*/g, '<strong><em>$1</em></strong>')
  .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  .replace(/(^|[^\w*])\*([^*\n]+)\*(?=[^\w*]|$)/g, '$1<em>$2</em>')
  .replace(/(^|[^\w_])_([^_\n]+)_(?=[^\w_]|$)/g, '$1<em>$2</em>')
  .replace(/~~([^~]+)~~/g, '<del>$1</del>')

const isTableDivider = (line) => /^\s*\|?[\s:|-]{3,}\|?\s*$/.test(line) && line.includes('-')
const cells = (line) => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim())

export function renderMarkdown(source) {
  const text = String(source ?? '')
  if (!text.trim()) return ''

  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const out = []
  let listTag = null
  let paragraph = []
  let fence = null

  const closeList = () => { if (listTag) { out.push(`</${listTag}>`); listTag = null } }
  const closeParagraph = () => {
    if (!paragraph.length) return
    out.push(`<p>${paragraph.map(inline).join('<br>')}</p>`)
    paragraph = []
  }

  for (let index = 0; index < lines.length; index++) {
    const line = lines[index].trimEnd()

    if (/^\s*```/.test(line)) {
      if (fence === null) {
        closeParagraph(); closeList(); fence = []
      } else {
        out.push(`<pre><code>${fence.map(escapeHtml).join('\n')}</code></pre>`)
        fence = null
      }
      continue
    }
    if (fence !== null) { fence.push(line); continue }

    if (!line.trim()) { closeParagraph(); closeList(); continue }

    const heading = /^(#{1,6})\s+(.*)$/.exec(line.trim())
    if (heading) {
      closeParagraph(); closeList()
      const level = Math.min(6, heading[1].length + 2)
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      continue
    }

    if (/^\s*([-*_])\1{2,}$/.test(line)) { closeParagraph(); closeList(); out.push('<hr>'); continue }

    if (line.includes('|') && index + 1 < lines.length && isTableDivider(lines[index + 1])) {
      closeParagraph(); closeList()
      const header = cells(line)
      index += 1
      const body = []
      while (index + 1 < lines.length && lines[index + 1].includes('|')) {
        index += 1
        body.push(cells(lines[index].trimEnd()))
      }
      out.push('<div class="md-table-wrap"><table><thead><tr>'
        + header.map(cell => `<th>${inline(cell)}</th>`).join('')
        + '</tr></thead><tbody>'
        + body.map(row => `<tr>${row.map(cell => `<td>${inline(cell)}</td>`).join('')}</tr>`).join('')
        + '</tbody></table></div>')
      continue
    }

    const quote = /^\s*>\s?(.*)$/.exec(line)
    if (quote) { closeParagraph(); closeList(); out.push(`<blockquote>${inline(quote[1])}</blockquote>`); continue }

    const bullet = /^\s*[-*+]\s+(.*)$/.exec(line)
    const numbered = /^\s*\d+[.)]\s+(.*)$/.exec(line)
    if (bullet || numbered) {
      closeParagraph()
      const wanted = bullet ? 'ul' : 'ol'
      if (listTag !== wanted) { closeList(); out.push(`<${wanted}>`); listTag = wanted }
      out.push(`<li>${inline((bullet || numbered)[1])}</li>`)
      continue
    }

    paragraph.push(line.trim())
  }

  if (fence !== null) out.push(`<pre><code>${fence.map(escapeHtml).join('\n')}</code></pre>`)
  closeParagraph(); closeList()
  return out.join('\n')
}

export default renderMarkdown