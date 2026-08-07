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
    <div style="margin-top:16px;">
      <button onclick="resetData()"
        style="background:transparent;color:#f87171;border:1px solid rgba(248,113,113,.4);
        border-radius:8px;padding:6px 16px;font-size:12px;cursor:pointer;">清空数据</button>
    </div>
    <div id="resetMsg" style="margin-top:8px;font-size:12px;color:#94a3b8;"></div>
  </div>
  <script>
    function resetData() {
      if (!confirm("确认清空全部累计使用人数统计？此操作不可恢复！")) return;
      var code = prompt("请输入确认码（默认 RESET-CONFIRM，可在 Worker 代码中修改）：", "RESET-CONFIRM");
      if (!code) return;
      fetch("/reset", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: code })
      }).then(function (r) { return r.json(); }).then(function (d) {
        var msg = document.getElementById("resetMsg");
        if (d.ok) {
          msg.textContent = "已清空 " + (d.cleared || 0) + " 条设备记录";
          msg.style.color = "#22c55e";
          document.getElementById("total").textContent = "0";
        } else {
          msg.textContent = "清空失败：" + (d.error || "未知错误");
          msg.style.color = "#f87171";
        }
      }).catch(function () {
        var msg = document.getElementById("resetMsg");
        msg.textContent = "网络错误，清空失败";
        msg.style.color = "#f87171";
      });
    }
  </script>
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

    // 清空统计数据（防误触：需确认码，可修改 RESET_CODE）
    if (path === '/reset') {
      if (request.method !== 'POST') {
        return json({ ok: false, error: 'Method Not Allowed' }, 405);
      }
      const RESET_CODE = 'RESET-CONFIRM';
      let body = {};
      try { body = await request.json(); } catch (e) {}
      if (!body || body.code !== RESET_CODE) {
        return json({ ok: false, error: 'invalid code' }, 403);
      }
      let deleted = 0;
      let cursor;
      do {
        const page = await env.COUNTS.list({ prefix: 'seen:', cursor });
        for (const k of page.keys) {
          await env.COUNTS.delete(k.name);
          deleted++;
        }
        cursor = page.cursor;
      } while (cursor);
      await env.COUNTS.delete('total');
      return json({ ok: true, cleared: deleted, total: 0 });
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
