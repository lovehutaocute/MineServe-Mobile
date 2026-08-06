package com.mineserve.mobile.data

/**
 * 使用统计后端配置（Cloudflare Worker）。
 * 部署后把 WORKER_BASE_URL 替换为真实 Worker 域名，例如：
 *   https://mineserve-usage.your-subdomain.workers.dev
 */
object UsageConfig {
    const val WORKER_BASE_URL = "https://mineserve-usage.REPLACE_WITH_SUBDOMAIN.workers.dev"
}
