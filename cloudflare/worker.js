// MineServe Mobile 累计独立用户统计 Worker
// 接口：
//   POST /pulse  { deviceId: string }  设备上报（SHA-256 哈希去重，新设备才累加）
//   GET  /stats  -> { total: N }        当前累计独立用户数
//   GET  /      简单说明
// 全部响应带 CORS 头，供网页跨域读取展示。

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

    if (path === '/') {
      return new Response(
        'MineServe Mobile usage counter.\nGET /stats  ->  {"total": N}\nPOST /pulse -> {"deviceId": "..."}',
        { status: 200, headers: { ...base, 'Content-Type': 'text/plain; charset=utf-8' } }
      );
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
