---
title: سرور MCP
description: Artemis Studio از Model Context Protocol پشتیبانی می‌کند، پس یک دستیار می‌تواند زیر همان مجوزها و همان مسیر ممیزیِ یک کاربر انسانی، روی کلاسترهای واقعی شما پاسخ دهد.
---

# سرور MCP

Studio از [Model Context Protocol](https://modelcontextprotocol.io) پشتیبانی می‌کند، پس یک دستیار می‌تواند *«چرا `ORDERS.DLQ` انباشته شده»* را روی کلاسترهای واقعی شما پاسخ دهد، به‌جای حدس‌زدن.

سطح در معرض، شانزده ابزار **مبتنی بر قصد** است — `diagnose`، `message_action`، `broker_config_change` — نه آینه‌ای از REST API. یک آینه، context مدل را صرف لوله‌کشی می‌کند و سرهم‌کردن تشخیص را به خودش وامی‌گذارد؛ این ابزارها به‌جایش به شکل خودِ پرسش‌ها ساخته شده‌اند ([ADR-0045](/reference/adr/0045-mcp-server-is-a-capability-surface)، انگلیسی).

## گرفتن یک کلید

وارد شوید ← منوی آواتار ← **Account** ← **API keys** ← **New key** ← دامنه و مجوزهایی که باید حمل کند را انتخاب کنید ← مقدار را کپی کنید. فقط یک بار نمایش داده می‌شود.

## اتصال

`POST /mcp`، روی همان origin رابط کاربری، با کلید به‌عنوان bearer token.

```json
{
  "mcpServers": {
    "artemis-studio": {
      "type": "http",
      "url": "https://studio.example.com/mcp",
      "headers": { "Authorization": "Bearer as_..." }
    }
  }
}
```

بدون کلاینت هم می‌شود آزمودش:

```bash
curl -s https://studio.example.com/mcp \
  -H "Authorization: Bearer as_..." \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## چه چیزهایی هست

| نوع | نام | برای |
|---|---|---|
| Tool | `studio_help` | خودِ فهرست: هر ابزار، وضعیت و پارامترهایش |
| Tool | `diagnose` | یک کلاستر (نقش HA هر نود، split-brain، تأخیر replication، هشدارهای فعال) یا یک صف از ابتدا تا انتها |
| Tool | `list_resources` | صف‌ها، آدرس‌ها، مصرف‌کننده‌ها، نشست‌ها، اتصال‌ها، تولیدکننده‌ها، divertها، bridgeها |
| Tool | `metric_series` | یک سری زمانی سطل‌بندی‌شده برای یک سنجه |
| Tool | `config_diff` | تفاوت‌های پیکربندی طبقه‌بندی‌شده میان دو نود |
| Tool | `broker_config` | اعلامیهٔ کلاستر، انحراف هر نود، قطعهٔ `broker.xml`، یا اعمال‌های گذشته |
| Tool | `browse_messages` | هدرها، یا یک بدنه بر اساس id |
| Tool | `trace_request_reply` | جریان‌ها، آمار تأخیر و timeout، انتظارات پیکربندی‌شده |
| Tool | `activity_log` | رویدادهای بروکر، یا مسیر ممیزی خود Studio |
| Tool | `message_action` | انتقال / retry / حذف / انقضا / purge |
| Tool | `queue_lifecycle` | ساخت، به‌روزرسانی، توقف، ازسرگیری یا نابودی یک صف، آدرس یا divert |
| Tool | `broker_config_change` | اعلام یک پیکربندی، یا اعمال آن اول روی قناری با تصدیق خطرها بر اساس شناسه |
| Tool | `connection_action` | بستن یک اتصال، نشست، مصرف‌کننده یا مصرف‌کننده‌های یک آدرس |
| Tool | `send_message` | صف‌کردن یک پیام |
| Tool | `alert_rule` / `studio_setting` | قواعد هشدار؛ تنظیمات عملیاتی |
| Resource | `studio://clusters`، `studio://permissions`، `studio://tools` | این کلید چه می‌بیند و چه می‌تواند بکند |
| Resource | `cluster://{id}/topology`، `cluster://{id}/capabilities`، `cluster://{id}/nodes/{nodeId}/settings` | نودها؛ اتصال از چه پشتیبانی می‌کند، به‌همراه `broker.xml` لازم برای باقی؛ تنظیمات مؤثر یک نود |
| Prompt | `triage_cluster`، `investigate_queue`، `before_you_purge`، `tune_scrape_load` | runbookها |

## قرارداد ایمنی

دستیاری که یک API مدیریتی در دست دارد، دقیقاً همان‌جایی است که مدل ایمنی باید کسل‌کننده و صریح باشد. این یکی هست:

- **یک کلید هرگز از مالکش فراتر نمی‌رود.** دسترسی‌ها در هر فراخوانی با دسترسی‌های *زندهٔ* مالک اشتراک گرفته می‌شوند، پس محدودکردن یک فرد بی‌درنگ کلیدهایش را هم محدود می‌کند ([ADR-0046](/reference/adr/0046-mcp-authenticates-with-existing-api-tokens)، انگلیسی).
- **تغییرها به‌طور پیش‌فرض dry-run اند.** یک اجرای مخرب واقعی افزون بر آن نیاز دارد `confirm` برابر نام خودِ صف باشد — که پرسشی جدا از `override` مربوط به سقف انبوه است و `confirm` هرگز آن را برآورده نمی‌کند.
- **همه‌چیز ممیزی می‌شود**، به نام مالک و با نام کلید ضمیمه‌شده (`ada [token: laptop-agent]`)، شامل dry runها.
- **کلاستری که کلید بر آن دسترسی ندارد** با این پاسخ برمی‌گردد: *«چنین کلاستری وجود ندارد، یا این کلید بر آن دسترسی ندارد»* — بدون نام‌بردن از مجوزی و بدون تأیید هیچ id ای.

## کشف

`studio_help` یک ابزار است، نه یک منبع اختیاری؛ و یک کاتالوگ واحد schemaها، متن راهنما، منبع و دستورالعمل‌های سرور را می‌سازد — پس نمی‌توانند با هم اختلاف پیدا کنند ([ADR-0054](/reference/adr/0054-mcp-discovery-is-a-tool-not-a-resource)، انگلیسی).
