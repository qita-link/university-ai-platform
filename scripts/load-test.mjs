#!/usr/bin/env node
/**
 * 轻量压测脚本（无需安装 k6 / ab）
 *
 * 用法：
 *   node scripts/load-test.mjs <baseUrl> <username> <password> [path] [并发数] [持续秒数]
 *   node scripts/load-test.mjs <baseUrl> - - [path] [并发数] [持续秒数]     # 免登录（测公开接口）
 * 示例：
 *   node scripts/load-test.mjs http://127.0.0.1:8080 20260001 'Campus@2026!Stu' /api/v1/timetables/me 200 15
 *
 * 说明：
 * - 先用账号登录拿令牌，然后所有并发请求都带同一个令牌（模拟已登录用户的读请求）
 * - 输出成功/失败数、RPS、以及 p50/p90/p95/p99 延迟
 * - 这是**读接口**压测；写接口（选课/审批）受行锁保护，容量另算
 */

const [, , baseUrl, username, password, path = '/api/v1/timetables/me', concArg = '200', secArg = '15'] =
  process.argv

if (!baseUrl || !username || !password) {
  console.error('用法: node scripts/load-test.mjs <baseUrl> <username> <password> [path] [并发] [秒数]')
  process.exit(1)
}

const concurrency = Number(concArg)
const seconds = Number(secArg)

const anonymous = username === '-'
let token = ''
if (!anonymous) {
  const loginRes = await fetch(`${baseUrl}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const loginBody = await loginRes.json().catch(() => null)
  token = loginBody?.data?.accessToken ?? ''
  if (!token) {
    console.error('登录失败:', JSON.stringify(loginBody).slice(0, 200))
    process.exit(1)
  }
}

const latencies = []
let ok = 0
let failed = 0
const statusCount = new Map()
const deadline = Date.now() + seconds * 1000

async function worker() {
  while (Date.now() < deadline) {
    const start = performance.now()
    try {
      const res = await fetch(`${baseUrl}${path}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      await res.arrayBuffer()
      const cost = performance.now() - start
      latencies.push(cost)
      statusCount.set(res.status, (statusCount.get(res.status) ?? 0) + 1)
      if (res.ok) ok++
      else failed++
    } catch {
      latencies.push(performance.now() - start)
      failed++
      statusCount.set('ERR', (statusCount.get('ERR') ?? 0) + 1)
    }
  }
}

const startedAt = Date.now()
await Promise.all(Array.from({ length: concurrency }, () => worker()))
const elapsed = (Date.now() - startedAt) / 1000

latencies.sort((a, b) => a - b)
const pick = (p) => latencies[Math.min(latencies.length - 1, Math.floor(latencies.length * p))] ?? 0
const total = ok + failed

console.log(`\n=== 压测结果 ===`)
console.log(`目标        ${path}`)
console.log(`并发        ${concurrency}   持续 ${seconds}s`)
console.log(`总请求      ${total}   成功 ${ok}   失败 ${failed}`)
console.log(`吞吐        ${(total / elapsed).toFixed(0)} req/s`)
console.log(`延迟(ms)    p50 ${pick(0.5).toFixed(0)}   p90 ${pick(0.9).toFixed(0)}   p95 ${pick(0.95).toFixed(0)}   p99 ${pick(0.99).toFixed(0)}   max ${(latencies.at(-1) ?? 0).toFixed(0)}`)
console.log(`状态码      ${[...statusCount.entries()].map(([k, v]) => `${k}:${v}`).join('  ')}`)
