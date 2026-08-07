var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// worker.js
var PAGE_TEMPLATE = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>MineServe Mobile \xB7 \u4F7F\u7528\u7EDF\u8BA1</title>
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
    <h1>\u7D2F\u8BA1\u4F7F\u7528\u4EBA\u6570</h1>
    <div class="sub">\u7D2F\u8BA1\u72EC\u7ACB\u7528\u6237\u7EDF\u8BA1 \xB7 \u6570\u636E\u5B9E\u65F6\u540C\u6B65</div>
    <div class="num"><b id="total">__TOTAL__</b></div>
    <div class="label"><span class="pulse"></span>\u4F4D\u7528\u6237\u6B63\u5728\u4F7F\u7528 MineServe Mobile \u8FD0\u884C\u670D\u52A1\u5668</div>
    <div class="meta">\u66F4\u65B0\u4E8E __UPDATED__ \xB7 \u6570\u636E\u7531 Cloudflare Workers \u7EDF\u8BA1</div>
  </div>
</body>
</html>`;
var worker_default = {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const base = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Cache-Control": "no-store"
    };
    const json = /* @__PURE__ */ __name((obj, status = 200) => new Response(JSON.stringify(obj), {
      status,
      headers: { ...base, "Content-Type": "application/json" }
    }), "json");
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: base });
    }
    const path = url.pathname;
    if (path === "/") {
      const total = parseInt(await env.COUNTS.get("total") || "0", 10);
      const updated = (/* @__PURE__ */ new Date()).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" });
      const html = PAGE_TEMPLATE.replace("__TOTAL__", total.toLocaleString("zh-CN")).replace("__UPDATED__", updated);
      return new Response(html, {
        status: 200,
        headers: { ...base, "Content-Type": "text/html; charset=utf-8" }
      });
    }
    if (path === "/stats") {
      const total = parseInt(await env.COUNTS.get("total") || "0", 10);
      return json({ total });
    }
    if (path === "/reset") {
      if (request.method !== "POST") {
        return json({ ok: false, error: "Method Not Allowed" }, 405);
      }
      const RESET_CODE = "RESET-CONFIRM";
      let body = {};
      try {
        body = await request.json();
      } catch (e) {
      }
      if (!body || body.code !== RESET_CODE) {
        return json({ ok: false, error: "invalid code" }, 403);
      }
      let deleted = 0;
      let cursor;
      do {
        const page = await env.COUNTS.list({ prefix: "seen:", cursor });
        for (const k of page.keys) {
          await env.COUNTS.delete(k.name);
          deleted++;
        }
        cursor = page.cursor;
      } while (cursor);
      await env.COUNTS.delete("total");
      return json({ ok: true, cleared: deleted, total: 0 });
    }
    if (path === "/pulse") {
      if (request.method !== "POST") {
        return json({ ok: false, error: "Method Not Allowed" }, 405);
      }
      let raw = "";
      try {
        const body = await request.json();
        raw = (body && body.deviceId ? body.deviceId : "").toString().trim();
      } catch (e) {
        raw = "";
      }
      if (!raw || raw.length > 128) {
        return json({ ok: false, error: "missing or invalid deviceId" }, 400);
      }
      const hash = await sha256(raw);
      const seenKey = "seen:" + hash;
      if (await env.COUNTS.get(seenKey)) {
        const total2 = parseInt(await env.COUNTS.get("total") || "0", 10);
        return json({ ok: true, newUser: false, total: total2 });
      }
      const total = parseInt(await env.COUNTS.get("total") || "0", 10) + 1;
      await env.COUNTS.put("total", total.toString());
      await env.COUNTS.put(seenKey, "1");
      return json({ ok: true, newUser: true, total });
    }
    return new Response("Not Found", { status: 404, headers: base });
  }
};
async function sha256(text) {
  const data = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
__name(sha256, "sha256");

// C:/Users/hvhvh/AppData/Local/Temp/wr/node_modules/wrangler/templates/middleware/middleware-ensure-req-body-drained.ts
var drainBody = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } finally {
    try {
      if (request.body !== null && !request.bodyUsed) {
        const reader = request.body.getReader();
        while (!(await reader.read()).done) {
        }
      }
    } catch (e) {
      console.error("Failed to drain the unused request body.", e);
    }
  }
}, "drainBody");
var middleware_ensure_req_body_drained_default = drainBody;

// C:/Users/hvhvh/AppData/Local/Temp/wr/node_modules/wrangler/templates/middleware/middleware-miniflare3-json-error.ts
function reduceError(e) {
  return {
    name: e?.name,
    message: e?.message ?? String(e),
    stack: e?.stack,
    cause: e?.cause === void 0 ? void 0 : reduceError(e.cause)
  };
}
__name(reduceError, "reduceError");
var jsonError = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } catch (e) {
    const error = reduceError(e);
    const body = JSON.stringify(error);
    const headers = {
      "Content-Type": "application/json",
      "MF-Experimental-Error-Stack": "true"
    };
    const encoded = encodeURIComponent(body);
    if (encoded.length <= 8192) {
      headers["MF-Experimental-Error-Stack-Payload"] = encoded;
    }
    return new Response(body, { status: 500, headers });
  }
}, "jsonError");
var middleware_miniflare3_json_error_default = jsonError;

// .wrangler/tmp/bundle-LhVWVk/middleware-insertion-facade.js
var __INTERNAL_WRANGLER_MIDDLEWARE__ = [
  middleware_ensure_req_body_drained_default,
  middleware_miniflare3_json_error_default
];
var middleware_insertion_facade_default = worker_default;

// C:/Users/hvhvh/AppData/Local/Temp/wr/node_modules/wrangler/templates/middleware/common.ts
var __facade_middleware__ = [];
function __facade_register__(...args) {
  __facade_middleware__.push(...args.flat());
}
__name(__facade_register__, "__facade_register__");
function __facade_invokeChain__(request, env, ctx, dispatch, middlewareChain) {
  const [head, ...tail] = middlewareChain;
  const middlewareCtx = {
    dispatch,
    next(newRequest, newEnv) {
      return __facade_invokeChain__(newRequest, newEnv, ctx, dispatch, tail);
    }
  };
  return head(request, env, ctx, middlewareCtx);
}
__name(__facade_invokeChain__, "__facade_invokeChain__");
function __facade_invoke__(request, env, ctx, dispatch, finalMiddleware) {
  return __facade_invokeChain__(request, env, ctx, dispatch, [
    ...__facade_middleware__,
    finalMiddleware
  ]);
}
__name(__facade_invoke__, "__facade_invoke__");

// .wrangler/tmp/bundle-LhVWVk/middleware-loader.entry.ts
var __Facade_ScheduledController__ = class ___Facade_ScheduledController__ {
  constructor(scheduledTime, cron, noRetry) {
    this.scheduledTime = scheduledTime;
    this.cron = cron;
    this.#noRetry = noRetry;
  }
  scheduledTime;
  cron;
  static {
    __name(this, "__Facade_ScheduledController__");
  }
  #noRetry;
  noRetry() {
    if (!(this instanceof ___Facade_ScheduledController__)) {
      throw new TypeError("Illegal invocation");
    }
    this.#noRetry();
  }
};
function wrapExportedHandler(worker) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return worker;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  const fetchDispatcher = /* @__PURE__ */ __name(function(request, env, ctx) {
    if (worker.fetch === void 0) {
      throw new Error("Handler does not export a fetch() function.");
    }
    return worker.fetch(request, env, ctx);
  }, "fetchDispatcher");
  return {
    ...worker,
    fetch(request, env, ctx) {
      const dispatcher = /* @__PURE__ */ __name(function(type, init) {
        if (type === "scheduled" && worker.scheduled !== void 0) {
          const controller = new __Facade_ScheduledController__(
            Date.now(),
            init.cron ?? "",
            () => {
            }
          );
          return worker.scheduled(controller, env, ctx);
        }
      }, "dispatcher");
      return __facade_invoke__(request, env, ctx, dispatcher, fetchDispatcher);
    }
  };
}
__name(wrapExportedHandler, "wrapExportedHandler");
function wrapWorkerEntrypoint(klass) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return klass;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  return class extends klass {
    #fetchDispatcher = /* @__PURE__ */ __name((request, env, ctx) => {
      this.env = env;
      this.ctx = ctx;
      if (super.fetch === void 0) {
        throw new Error("Entrypoint class does not define a fetch() function.");
      }
      return super.fetch(request);
    }, "#fetchDispatcher");
    #dispatcher = /* @__PURE__ */ __name((type, init) => {
      if (type === "scheduled" && super.scheduled !== void 0) {
        const controller = new __Facade_ScheduledController__(
          Date.now(),
          init.cron ?? "",
          () => {
          }
        );
        return super.scheduled(controller);
      }
    }, "#dispatcher");
    fetch(request) {
      return __facade_invoke__(
        request,
        this.env,
        this.ctx,
        this.#dispatcher,
        this.#fetchDispatcher
      );
    }
  };
}
__name(wrapWorkerEntrypoint, "wrapWorkerEntrypoint");
var WRAPPED_ENTRY;
if (typeof middleware_insertion_facade_default === "object") {
  WRAPPED_ENTRY = wrapExportedHandler(middleware_insertion_facade_default);
} else if (typeof middleware_insertion_facade_default === "function") {
  WRAPPED_ENTRY = wrapWorkerEntrypoint(middleware_insertion_facade_default);
}
var middleware_loader_entry_default = WRAPPED_ENTRY;
export {
  __INTERNAL_WRANGLER_MIDDLEWARE__,
  middleware_loader_entry_default as default
};
//# sourceMappingURL=worker.js.map
