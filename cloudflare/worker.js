// MineServe Mobile 累计独立用户统计 Worker
// 接口：
//   GET  /            返回完整前端统计页（服务端内联最新数据）
//   GET  /stats       -> { total: N }   累计独立用户数
//   POST /pulse       { deviceId }      设备上报（SHA-256 去重，新设备才累加）
// 全部响应带 CORS 头，供网页跨域读取展示。

const PAGE_TEMPLATE = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>MineServe Mobile · 使用统计</title>
<style>
  * { box-sizing: border-box; }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
    background: radial-gradient(1200px 600px at 20% 10%, #312e81 0%, transparent 50%),
                radial-gradient(1000px 500px at 80% 90%, #1e3a8a 0%, transparent 50%),
                #0f172a;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
    color: #e2e8f0; padding: 24px; }
  .card { background: rgba(255,255,255,.05); border:1px solid rgba(255,255,255,.1);
    border-radius: 24px; padding: 44px 56px; text-align:center; backdrop-filter: blur(10px);
    box-shadow: 0 24px 70px rgba(0,0,0,.45); max-width: 92vw; width: 520px; }
  .badge { display:inline-block; background:#4f46e5; color:#fff; font-size:12px; letter-spacing:2px;
    padding:5px 14px; border-radius:999px; font-weight:600; }
  h1 { font-size:24px; margin:18px 0 6px; color:#fff; font-weight:700; }
  .sub { color:#94a3b8; font-size:14px; margin-bottom:26px; }
  .num { font-size:84px; font-weight:800; color:#fff; line-height:1; font-variant-numeric: tabular-nums; }
  .num b { color:#818cf8; }
  .label { color:#94a3b8; font-size:15px; margin-top:10px; }
  .meta { margin-top:28px; color:#64748b; font-size:12px; border-top:1px solid rgba(255,255,255,.08);
    padding-top:16px; }
  .pulse { display:inline-block; width:8px; height:8px; border-radius:50%; background:#22c55e;
    margin-right:7px; animation: blink 1.6s infinite; }
  @keyframes blink { 0%,100%{opacity:1} 50%{opacity:.25} }
  @media (max-width:480px) { .card { padding:32px 24px; } .num { font-size:64px; } }
</style>
</head>
<body>
  <div class="card">
    <span class="badge">MINESERVE MOBILE</span>
    <h1>累计使用人数</h1>
    <div class="sub">累计独立用户统计 · 数据实时同步</div>
    <div class="num"><b id="total">__TOTAL__</b></div>
    <div class="label"><span class="pulse"></span>位用户正在使用 MineServe Mobile 运行服务器</div>
    <div class="meta">更新于 __UPDATED__ · 数据由 Cloudflare Workers 统计</div>
  </div>
</body>
</html>`;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const base = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
      'Cache-Control': 'no-store',
    };
    const json = (obj, status = 200) =>
      new Response(JSON.stringify(obj), {
        status,
        headers: { ...base, 'Content-Type': 'application/json' },
      });

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: base });
    }

    const path = url.pathname;

    // 完整前端统计页（服务端内联最新数据，打开即有数字）
    if (path === '/') {
      const total = parseInt((await env.COUNTS.get('total')) || '0', 10);
      const updated = new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
      const html = PAGE_TEMPLATE
        .replace('__TOTAL__', total.toLocaleString('zh-CN'))
        .replace('__UPDATED__', updated);
      return new Response(html, {
        status: 200,
        headers: { ...base, 'Content-Type': 'text/html; charset=utf-8' },
      });
    }

    // 累计独立用户数
    if (path === '/stats') {
      const total = parseInt((await env.COUNTS.get('total')) || '0', 10);
      return json({ total });
    }

    // 设备上报（幂等：同一设备只计一次）
    if (path === '/pulse') {
      if (request.method !== 'POST') {
        return json({ ok: false, error: 'Method Not Allowed' }, 405);
      }
      let raw = '';
      try {
        const body = await request.json();
        raw = (body && body.deviceId ? body.deviceId : '').toString().trim();
      } catch (e) {
        raw = '';
      }
      if (!raw || raw.length > 128) {
        return json({ ok: false, error: 'missing or invalid deviceId' }, 400);
      }

      const hash = await sha256(raw);
      const seenKey = 'seen:' + hash;

      // 已见过该设备：直接返回当前总数，不重复累加
      if (await env.COUNTS.get(seenKey)) {
        const total = parseInt((await env.COUNTS.get('total')) || '0', 10);
        return json({ ok: true, newUser: false, total });
      }

      // 新设备：累加总数并标记
      // 注：读-改-写存在极低概率并发竞态，免费档规模下误差可忽略
      const total = parseInt((await env.COUNTS.get('total')) || '0', 10) + 1;
      await env.COUNTS.put('total', total.toString());
      await env.COUNTS.put(seenKey, '1');
      return json({ ok: true, newUser: true, total });
    }

    return new Response('Not Found', { status: 404, headers: base });
  },
};

async function sha256(text) {
  const data = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}
