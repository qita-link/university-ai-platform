// 校园 AI 平台 - 前端静态托管 + 后端反向代理（无需 nginx）
// 用法：node deploy/serve.mjs   环境变量 PORT（默认 5173）、API_TARGET（默认 http://127.0.0.1:8080）
import http from 'node:http'
import { createReadStream, statSync } from 'node:fs'
import { extname, join, normalize, resolve } from 'node:path'

const PORT = Number(process.env.PORT || 5173)
const API_TARGET = new URL(process.env.API_TARGET || 'http://127.0.0.1:8080')
const ROOT = resolve(process.cwd(), 'frontend/dist')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8'
}

const PROXY_PREFIXES = ['/api', '/actuator', '/v3/api-docs', '/swagger-ui', '/swagger-resources', '/webjars']

function sendFile(res, filePath) {
  const type = MIME[extname(filePath).toLowerCase()] || 'application/octet-stream'
  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' })
  createReadStream(filePath).pipe(res)
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)

  if (PROXY_PREFIXES.some((p) => url.pathname === p || url.pathname.startsWith(p + '/'))) {
    const proxyReq = http.request(
      {
        hostname: API_TARGET.hostname,
        port: API_TARGET.port || 80,
        path: req.url,
        method: req.method,
        headers: { ...req.headers, host: API_TARGET.host }
      },
      (proxyRes) => {
        res.writeHead(proxyRes.statusCode || 502, proxyRes.headers)
        proxyRes.pipe(res)
      }
    )
    proxyReq.on('error', (err) => {
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
      res.end(JSON.stringify({ code: 'GATEWAY_ERROR', message: '后端未就绪: ' + err.message }))
    })
    req.pipe(proxyReq)
    return
  }

  let pathname = decodeURIComponent(url.pathname)
  if (pathname.endsWith('/')) pathname += 'index.html'
  const candidate = normalize(join(ROOT, pathname))
  if (!candidate.startsWith(ROOT)) {
    res.writeHead(403).end('Forbidden')
    return
  }

  try {
    if (statSync(candidate).isFile()) {
      sendFile(res, candidate)
      return
    }
  } catch {
    // 交给 SPA 兜底
  }

  // 带扩展名的静态资源缺失 → 404，其余走前端路由
  if (extname(pathname)) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('Not Found')
    return
  }
  sendFile(res, join(ROOT, 'index.html'))
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[frontend] http://127.0.0.1:${PORT}  ->  dist: ${ROOT}`)
  console.log(`[frontend] /api 代理到 ${API_TARGET.origin}`)
})
