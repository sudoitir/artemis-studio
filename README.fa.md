<div align="center">

# Artemis Studio

**یک کنسول برای هر کلاستر Apache ActiveMQ Artemis که اجرا می‌کنید.**

توپولوژی زنده، همهٔ صف‌های همهٔ نودها در یک جدول، عملیات امن روی پیام‌ها، و SQL روی پیام‌ها — از یک نمونهٔ واحد.

[English](README.md) · [简体中文](README.zh.md) · **فارسی**

[![CI](https://github.com/sudoitir/artemis-studio/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sudoitir/artemis-studio/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/sudoitir/artemis-studio?include_prereleases&sort=semver&label=release)](https://github.com/sudoitir/artemis-studio/releases)
[![Docker pulls](https://img.shields.io/docker/pulls/sudoit1/artemis-studio?logo=docker&label=pulls)](https://hub.docker.com/r/sudoit1/artemis-studio)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/sudoitir/artemis-studio)](https://github.com/sudoitir/artemis-studio/commits/main)
[![Stars](https://img.shields.io/github/stars/sudoitir/artemis-studio?style=flat)](https://github.com/sudoitir/artemis-studio/stargazers)

[**مستندات**](https://sudoitir.github.io/artemis-studio/fa/) ·
[شروع سریع](https://sudoitir.github.io/artemis-studio/fa/guide/quickstart) ·
[کنسول SQL](https://sudoitir.github.io/artemis-studio/fa/guide/sql-console) ·
[MCP](https://sudoitir.github.io/artemis-studio/fa/guide/mcp)

</div>

<div dir="rtl" align="right">

> [!WARNING]
> **نسخهٔ آلفا.** در حال توسعهٔ فعال و هنوز کامل نیست. ایمیج‌های منتشرشده buildهای dev پیش از پایدار هستند (`sudoit1/artemis-studio:dev` — هنوز بدون `:latest`). تغییرات ناسازگار را انتظار داشته باشید.

</div>

![Artemis Studio: توپولوژی، جدول صف‌ها در همهٔ نودها، صف dead-letter و نمودارها](docs/img/demo.gif)

<div dir="rtl" align="right">

## چرا

کنسولی که همراه آرتمیس می‌آید **هر بار یک بروکر** را مدیریت می‌کند و اصلاً نمی‌داند کلاستری در کار است. تا وقتی پرسش شما از مرز یک نود عبور نکند مشکلی نیست — اما پرسش‌هایی که اهمیت دارند همیشه عبور می‌کنند: *کدام نود live است*، *عمق کجاست*، *آن پیام کجا رفت*.

Artemis Studio چیز دیگری است: **یک نمونه، روی چندین کلاستر**، با کلاستر به‌عنوان واحد همه‌چیز. روی بروکرهای **موجود** شما اجرا می‌شود — جز فعال‌کردن endpointهای مدیریتی که تقریباً حتماً همین حالا هم دارید نیازی به بازنویسی `broker.xml` نیست — و هرگز بروکری را بالا نمی‌آورد.

## اجرایش کنید

</div>

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up          # Studio و Postgres، با کلیدهای تولیدشده و پین‌شده به آخرین انتشار
```

<div dir="rtl" align="right">

سپس <http://localhost:8080> را باز کنید. `just up` رمز تولیدشدهٔ `admin` را یک بار چاپ می‌کند؛ در اولین ورود مجبور می‌شوید تغییرش دهید.

<details>
<summary>بدون <code>just</code>، یا روی Postgres خودتان</summary>

</div>

```bash
base=https://raw.githubusercontent.com/sudoitir/artemis-studio/main/deploy/compose
curl -sO "$base/compose.prod.yaml"
curl -s "$base/.env.example" -o .env   # سپس ویرایشش کنید
docker compose -f compose.prod.yaml --env-file .env up -d
```

```bash
docker run -p 8080:8080 \
  -e ARTEMIS_STUDIO_DB_URL=jdbc:postgresql://db:5432/artemis_studio \
  -e ARTEMIS_STUDIO_DB_USER=artemis_studio \
  -e ARTEMIS_STUDIO_DB_PASSWORD=... \
  -e ARTEMIS_STUDIO_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/artemis-studio:dev
```

<div dir="rtl" align="right">

هر متغیر، الزام reverse proxy برای جریان SSE، و بازیابی اولین ورود، همه در [راهنمای پیکربندی](https://sudoitir.github.io/artemis-studio/fa/guide/configuration) آمده‌اند.

</details>

## SQL روی پیام‌هایتان

آرتمیس نمی‌تواند به *«سفارش ۴۴۷۱ کجا رفت؟»* پاسخ دهد. تنها فیلتر سمت سرورش JMS selector است: فقط هدرهای پیام — **هرگز بدنه** — و هر بار یک صف روی یک نود.

</div>

```sql
SELECT * FROM "ORDER.*"
WHERE body->>'orderId' = '4471'
LIMIT 50
```

![کنسول SQL: کوئری روی همهٔ صف‌های کلاستر، با هزینهٔ طبقه‌بندی‌شده پیش از اجرا، سپس یک tail زنده](docs/img/sql-console.gif)

<div dir="rtl" align="right">

یک گویش محدود و فقط‌خواندنی — تنها `SELECT`، تجزیه‌شده به AST و اعتبارسنجی‌شده در برابر یک کاتالوگ ستون ثابت، پس هیچ تغییری اصلاً قابل بیان نیست. predicateهای روی هدر به JMS selector تبدیل می‌شوند و هزینه‌ای ندارند؛ predicateهای روی بدنه یک scan هستند. **نوار plan پیش از اجرای کوئری می‌گوید کدام‌یک است**، و کوئری‌ای که از سقف هزینه بگذرد به‌جای بریده‌شدن، همراه با برآورد رد می‌شود — نتیجهٔ بریده‌شده در یک نگاه از نتیجهٔ کامل قابل تشخیص نیست.

به آن یک tail زنده (یک poll، هرگز یک consume) و یک ایندکس اختیاریِ محدود به دورهٔ نگهداری برای پیام‌هایی که پیشتر مصرف شده‌اند اضافه کنید. [بیشتر ←](https://sudoitir.github.io/artemis-studio/fa/guide/sql-console)

## دیگر چه می‌کند

| توپولوژی کلاستر | صف‌ها در همهٔ نودها |
|---|---|
| [![توپولوژی live/backup با replication و محور NodeID مشترک](docs/img/topology.png)](docs/img/topology.png) | [![همهٔ صف‌ها در همهٔ نودها در یک جدول مجازی‌سازی‌شده](docs/img/queues.png)](docs/img/queues.png) |
| **سنجه‌ها و نمودارها** | **حاکمیت** |
| [![نمودارهای عمق، توان عملیاتی و مصرف‌کننده](docs/img/metrics.png)](docs/img/metrics.png) | [![کاربران، دسترسی‌های دامنه‌دار، محیط‌ها، توکن‌های API و نگاشت claimهای OIDC](docs/img/governance.png)](docs/img/governance.png) |

- **توپولوژی** — جفت‌های live/backup و وضعیت replication، با نقش HA که در هر چرخه از هر نود poll می‌شود. هرگز از پیکربندی خوانده نمی‌شود؛ دو نود live در یک جفت یعنی هشدار split-brain.
- **منابع در همهٔ نودها** — صف‌ها، آدرس‌ها، مصرف‌کننده‌ها، نشست‌ها، اتصال‌ها و تولیدکننده‌ها در یک جدول مجازی‌سازی‌شده، منتسب به هر نود، روی SSE.
- **عملیات پیام** — مرور، ارسال، انتقال، retry، انقضا، حذف و purge، با `?dryRun=true` روی هر فراخوانی تغییردهنده، سقف انبوهِ اعمال‌شده در سمت سرور، و نتایج به تفکیک نود.
- **ردیابی request-reply** — تناظر درخواست‌ها با پاسخ‌ها در سراسر آدرس‌ها و نودها، با آمار تأخیر و timeout در برابر انتظارات اعلام‌شده.
- **حاکمیت** — احراز هویت در همه‌جا، مدل نقش/مجوز در دامنه‌های global ← environment ← cluster، توکن‌های API، OIDC/SSO اختیاری، و رویداد ممیزی نوشته‌شده در همان تراکنشِ فرمان.
- **[سرور MCP](https://sudoitir.github.io/artemis-studio/fa/guide/mcp)** — همان قابلیت‌ها برای یک دستیار، زیر همان دسترسی‌ها و همان مسیر ممیزی.

## بر پایهٔ چهار قاعده

**ذاتاً مهربان با بروکر** — خواندن‌های دسته‌ای (یک POST جولوکیا برای هر نود، هرگز یکی برای هر صف)، poll لایه‌بندی‌شده، و محدودکنندهٔ نرخ به‌ازای هر نود. Studio هرگز نباید دلیل از پا درآمدن یک بروکر باشد.
**امن به‌صورت پیش‌فرض** — هر فراخوانی مخرب dry-run دارد؛ purge و delete نیازمند تایپ‌کردن نام منبع‌اند.
**هرگز برای وضعیت HA به پیکربندی اعتماد نکن** — اینکه چه کسی live است را باید از نودهای زنده پرسید.
**گیت‌کردن صادقانهٔ قابلیت‌ها** — قابلیتِ در دسترس نبوده همین را می‌گوید و دقیقاً همان `broker.xml` ای را که فعالش می‌کند نشان می‌دهد. هیچ‌چیز بی‌صدا غایب نمی‌شود.

## توسعه

نیازمند JDK 25، Node 22، Docker و [`just`](https://github.com/casey/just#packages). یک dev container هم فراهم است (`.devcontainer/`).

</div>

```bash
just dev-up          # Postgres + یک جفت واقعی primary/backup آرتمیس + Studio، از سورس
just dev             # یا: بک‌اند :8080 و Vite :5173، با هم و با live reload
just verify          # هر آنچه CI اجرا می‌کند
```

<div dir="rtl" align="right">

فرمان `ADMIN_PASSWORD=… just demo` یک جفت live/backup دوم اضافه می‌کند و هر چهار نود را با ترافیک واقع‌نما پر می‌کند — یک آدرس بدون مصرف‌کننده که عمقش بالا می‌رود، یک انباشت واقعی dead-letter، و یک نود متوقف‌شده. تصاویر و GIFهای بالا با `just shots` و `just demo-gif` از همان ضبط شده‌اند؛ هیچ‌چیز صحنه‌سازی نشده است.

هر قابلیت از مسیر **OpenSpec** می‌گذرد (`/opsx:propose` ← `apply` ← `archive`)، و تصمیم‌های مهم [**ADR**](docs/adr/) می‌گیرند. [`CLAUDE.md`](CLAUDE.md) و [`CONTRIBUTING.md`](CONTRIBUTING.md) را ببینید.

## پشتهٔ فناوری

Java 25 · Spring Boot 4.1 · PostgreSQL با Liquibase · React 19 + Vite + Mantine 9 · TanStack Router/Query/Table · React Flow · اول Jolokia روی HTTP و سپس کلاینت Core آرتمیس · SSE · یک ایمیج کانتینر.
[معماری](https://sudoitir.github.io/artemis-studio/reference/architecture) ·
[هر ۶۱ تصمیم](https://sudoitir.github.io/artemis-studio/reference/adr/) (انگلیسی).

## انتشارها

هر push به `main` یک انتشار منتشر می‌کند. CalVer به شکل `YYYY.MM.PATCH`، و سه تگ روی Docker Hub — `2026.09.3` (تغییرناپذیر)، `2026.09` (همان ماه)، `dev` (آخرین). تا اولین انتشار پایدار، `:latest` منتشر نمی‌شود. هر انتشار jar قابل اجرا را همراه یک `.sha256` ضمیمه می‌کند و یادداشت‌هایش از پیام‌های commit ساخته می‌شوند ([`changelog/`](changelog/)).

## نقشهٔ راه

فازهای ۰ تا ۸ انجام شده‌اند: توپولوژی، نماهای چندنودی، عملیات پیام، مسیر ممیزی، کلاینت Core و ردیابی request-reply، سنجه‌ها، هشدارها، حاکمیت، سرور MCP، و کنسول SQL. آنچه مانده:

|  | |
|--|--|
| [ ] | مدیریت divert و bridge |
| [ ] | وضعیت مطلوبِ اعلام‌شده و تشخیص drift |
| [ ] | HA چندنمونه‌ای — یک advisory lock پستگرس به‌ازای هر کلاستر |
| [ ] | چارت Helm |
| [ ] | بازپخش پیام از payload ضبط‌شده |
| [ ] | یکپارچگی با اپراتور ArkMQ، انتقال JMX، نماهای ذخیره‌شده، گزارش‌های زمان‌بندی‌شده |

## پروانه

[Apache-2.0](LICENSE) — همان پروانهٔ خود آرتمیس.

Apache ActiveMQ و Apache ActiveMQ Artemis علائم تجاری بنیاد نرم‌افزار آپاچی هستند. Artemis Studio پروژه‌ای مستقل است و توسط بنیاد نرم‌افزار آپاچی تولید، تأیید یا وابسته به آن نیست. ارجاع‌ها به «Artemis» به بروکری اشاره دارند که این ابزار مدیریتش می‌کند.

</div>

---

<div align="center">

اگر وقتتان را ذخیره کرد، یک ⭐ کمک می‌کند دیگران هم پیدایش کنند.

</div>
