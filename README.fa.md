<div align="center">

# Artemis Studio

**یک کنسول برای همهٔ کلاسترهای Apache ActiveMQ Artemis که دارید.**

توپولوژی زنده، همهٔ صف‌های همهٔ نودها در یک جدول، مسیر پیام‌ها جلوی چشمتان، عملیات امن روی پیام‌ها و جست‌وجوی پیام‌ها با SQL — همه از یک نمونهٔ Studio.

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
[MCP](https://sudoitir.github.io/artemis-studio/fa/guide/mcp) ·
[نقشهٔ راه](README.md#roadmap)

</div>

> [!WARNING]
> **نسخهٔ آلفا.** پروژه در حال توسعه است و هنوز همهٔ قابلیت‌هایش را ندارد. ایمیج‌های منتشرشده نسخهٔ dev و پیش از انتشار پایدارند (`sudoit1/artemis-studio:dev`؛ هنوز `:latest` نداریم). منتظر تغییرات ناسازگار باشید.

![Artemis Studio: توپولوژی، جدول صف‌های همهٔ نودها، صف dead-letter، مسیر پیام‌ها و نمودارها](docs/img/demo.gif)

<div dir="rtl" align="right">

## چه کارهایی می‌کند

| توپولوژی کلاستر | صف‌های همهٔ نودها |
|---|---|
| [![توپولوژی live/backup همراه با وضعیت replication و محور NodeID مشترک](docs/img/topology.png)](docs/img/topology.png) | [![همهٔ صف‌های همهٔ نودها در یک جدول مجازی](docs/img/queues.png)](docs/img/queues.png) |
| **کلاینت‌ها و مسیر پیام** | **کنسول SQL** |
| [![Flow: نقطه‌های متحرک نرخ هر مسیر را نشان می‌دهند و با رفتن نشانگر روی یک صف، کل مسیرش برجسته می‌شود](docs/img/flow.gif)](docs/img/flow.gif) | [![کنسول SQL: کوئری روی همهٔ صف‌های کلاستر، با برآورد هزینه پیش از اجرا و سپس tail زنده](docs/img/sql-console.gif)](docs/img/sql-console.gif) |
| **متریک‌ها و نمودارها** | **حاکمیت** |
| [![نمودارهای عمق صف، توان عملیاتی و مصرف‌کننده‌ها از Postgres پارتیشن‌شده](docs/img/metrics.png)](docs/img/metrics.png) | [![کاربران، دسترسی‌های محدودشده، محیط‌ها، توکن‌های API و نگاشت claimهای OIDC](docs/img/governance.png)](docs/img/governance.png) |

- **توپولوژی** — جفت‌های live/backup و وضعیت replication آن‌ها. نقش HA در هر چرخه مستقیماً از خود نودها خوانده می‌شود، نه از فایل پیکربندی؛ اگر در یک جفت دو نود همزمان live باشند، هشدار split-brain داده می‌شود.
- **Flow (مسیر پیام)** — می‌بینید کدام اپلیکیشن به کدام آدرس پیام می‌فرستد، پیام از چه divert، bridge یا پرش بین نودهای کلاستر به کدام صف می‌رسد و چه کسی با چه نرخی مصرفش می‌کند. مشکلاتی مثل انباشت پیام در صفی که مصرف‌کننده ندارد با کلمات روشن گفته می‌شود، و کلاینت‌ها فقط وقتی کسی این صفحه را نگاه می‌کند نمونه‌برداری می‌شوند.
- **[کنسول SQL](https://sudoitir.github.io/artemis-studio/fa/guide/sql-console)** — Artemis نمی‌تواند بگوید *سفارش ۴۴۷۱ کجا رفت*. تنها فیلتر سمت سرورش JMS selector است: فقط هدرها را می‌بیند، هیچ‌وقت بدنهٔ پیام را نه، آن هم هر بار فقط یک صف روی یک نود. Studio این کار را می‌کند:

</div>

```sql
SELECT * FROM "ORDER.*"
WHERE body->>'orderId' = '4471'
LIMIT 50
```

<div dir="rtl" align="right">

زبان کوئری فقط `SELECT` فقط‌خواندنی است و با فهرست ثابتی از ستون‌ها سنجیده می‌شود، پس هیچ کوئری‌ای نمی‌تواند چیزی را تغییر دهد. شرط‌های روی هدر به JMS selector تبدیل می‌شوند و تقریباً هزینه‌ای ندارند؛ شرط‌های روی بدنه نیاز به پیمایش دارند، و **نوار plan پیش از اجرا می‌گوید کوئری شما کدام است**. کوئری‌ای که از سقف هزینه بگذرد همراه با برآوردش رد می‌شود، نه اینکه بی‌صدا نتیجه‌اش بریده شود. می‌توانید tail زنده را روشن کنید یا برای یک صف [capture کامل](https://sudoitir.github.io/artemis-studio/fa/guide/message-capture) را فعال کنید: یک کپی با ظرفیت محدود روی خود بروکر که Studio آن را مصرف می‌کند، تا حتی پیامی که بین دو poll مصرف شده باز هم قابل جست‌وجو باشد.

- **منابع همهٔ نودها** — صف‌ها، آدرس‌ها، مصرف‌کننده‌ها، نشست‌ها، اتصال‌ها و تولیدکننده‌ها در یک جدول مجازی؛ هر ردیف نشان می‌دهد مال کدام نود است و از طریق SSE به‌روز می‌ماند.
- **عملیات روی پیام** — مرور، ارسال، جابه‌جایی، retry، منقضی‌کردن، حذف و purge. هر فراخوانی تغییردهنده `?dryRun=true` را می‌پذیرد، عملیات گروهی در سمت سرور سقف دارد و نتیجه به تفکیک نود گزارش می‌شود.
- **ردیابی request-reply** — درخواست‌ها را در همهٔ آدرس‌ها و نودها به پاسخ‌هایشان وصل می‌کند و آمار تأخیر و timeout را با انتظاری که تعریف کرده‌اید می‌سنجد.
- **[پیکربندی بروکر](https://sudoitir.github.io/artemis-studio/fa/guide/broker-configuration)** — address settingها، security settingها، divertها و صف‌هایی را که کلاستر باید داشته باشد تعریف کنید. آن‌ها را اول روی یک نود قناری اعمال کنید، در حالی که پیش از هر نوشتن همهٔ خطرها گفته می‌شود، یا به‌صورت بخشی از `broker.xml` خروجی بگیرید و ببینید هر نود کجا از تعریف فاصله گرفته است. در اولین استفاده، Studio وضعیت فعلی کلاستر را به‌عنوان نسخهٔ ۱ پیشنهاد می‌دهد، ولی بدون تأیید شما چیزی را نمی‌پذیرد.
- **حاکمیت داده** — هدرها و propertyهای حساس پوشانده می‌شوند، اطلاعات شخصی (PII) خودکار شناسایی می‌شود و نمایش یا پوشاندن آن به نقش بیننده بستگی دارد.
- **حاکمیت** — احراز هویت در همه‌جا، نقش‌ها و مجوزها در سه سطح سراسری ← محیط ← کلاستر، توکن‌های API، OIDC/SSO اختیاری، و ثبت هر تغییر و نتیجه‌اش در گزارش ممیزی.
- **[سرور MCP](https://sudoitir.github.io/artemis-studio/fa/guide/mcp)** — همین قابلیت‌ها برای دستیار هوش مصنوعی، با همان مجوزها و همان گزارش ممیزی.

## چرا Artemis Studio

کنسولی که همراه Artemis می‌آید **هر بار فقط یک بروکر** را مدیریت می‌کند و اصلاً از وجود کلاستر خبر ندارد. تا وقتی سؤالتان به یک نود محدود است مشکلی نیست، ولی سؤال‌های مهم همیشه چند نود را درگیر می‌کنند: *الان کدام نود live است*، *پیام‌ها کجا انباشته شده‌اند*، *آن پیام کجا رفت*.

Artemis Studio **کلاستر را واحد همه‌چیز** می‌داند و یک نمونه‌اش برای همهٔ کلاسترهایتان کافی است. روی بروکرهای **فعلی** شما کار می‌کند — جز endpointهای مدیریتی که به احتمال زیاد همین حالا فعال‌اند، لازم نیست به `broker.xml` دست بزنید — و هیچ‌وقت خودش بروکری راه نمی‌اندازد.

## راه‌اندازی

</div>

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up          # Studio و Postgres، با کلیدهای ساخته‌شده و قفل‌شده روی آخرین نسخه
```

<div dir="rtl" align="right">

سپس <http://localhost:8080> را باز کنید. `just up` رمز ساخته‌شدهٔ `admin` را فقط یک بار نشان می‌دهد و در اولین ورود باید آن را عوض کنید.

</div>

<details>
<summary dir="rtl">بدون <code>just</code>، یا با Postgres خودتان</summary>

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

همهٔ متغیرها، تنظیمی که reverse proxy برای جریان SSE لازم دارد و راه بازیابی اولین ورود در [راهنمای پیکربندی](https://sudoitir.github.io/artemis-studio/fa/guide/configuration) آمده است.

</div>

</details>

<div dir="rtl" align="right">

## چهار اصل

- **از پایه، ملاحظهٔ بروکر را می‌کند.** خواندن‌ها دسته‌ای است (برای هر نود یک POST به Jolokia، نه یکی برای هر صف)، polling لایه‌بندی شده و برای هر نود محدودیت نرخ وجود دارد. Studio هرگز نباید دلیل از کار افتادن یک بروکر باشد.
- **پیش‌فرض، امنیت است.** هر عملیات مخرب را می‌شود اول به‌صورت dry run اجرا کرد، و برای purge و حذف باید نام منبع را تایپ کنید.
- **وضعیت HA را از نودها بپرس، نه از پیکربندی.** اینکه چه کسی live است را فقط نودهای در حال اجرا می‌دانند.
- **صراحت دربارهٔ محدودیت‌ها.** اگر قابلیتی در دسترس نباشد، همین را می‌گوید و دقیقاً همان `broker.xml` لازم برای فعال‌کردنش را نشان می‌دهد. هیچ قابلیتی بی‌صدا ناپدید نمی‌شود.

## توسعه

به JDK 25، Node 22، Docker و [`just`](https://github.com/casey/just#packages) نیاز دارید. یک dev container هم آماده است (`.devcontainer/`).

</div>

```bash
just dev-up          # Postgres + یک جفت واقعی primary/backup از Artemis + Studio، از روی سورس
just dev             # یا: بک‌اند :8080 و Vite :5173 با هم، همراه با live reload
just verify          # همهٔ بررسی‌های CI
```

<div dir="rtl" align="right">

فرمان `ADMIN_PASSWORD=… just demo` یک جفت live/backup دیگر اضافه می‌کند و هر چهار نود را با ترافیکی شبیه محیط واقعی پر می‌کند: اپلیکیشن‌هایی پشت divert، bridge و پرش‌های کلاستر، آدرسی بدون مصرف‌کننده که صفش پیوسته پرتر می‌شود، انباشت واقعی dead-letter و یک نود خاموش. در محیط تازه، رمز چاپ‌شده یک‌بارمصرف است؛ پس بار اول `NEW_ADMIN_PASSWORD=…` را هم بدهید و از آن به بعد همان رمز جدید را به کار ببرید. همهٔ تصاویر و ویدیوهای بالا با `just shots` و `just demo-gif` از همین محیط ضبط شده‌اند و هیچ‌چیزشان ساختگی نیست.

هر قابلیت از فرایند **OpenSpec** می‌گذرد (`/opsx:propose` ← `apply` ← `archive`) و تصمیم‌های مهم به‌صورت [**ADR**](docs/adr/) ثبت می‌شوند. [`CLAUDE.md`](CLAUDE.md) و [`CONTRIBUTING.md`](CONTRIBUTING.md) را ببینید.

## فناوری‌ها

Java 25 · Spring Boot 4.1 · PostgreSQL با Liquibase · React 19 + Vite + Mantine 9 · TanStack Router/Query/Table · React Flow · در درجهٔ اول Jolokia روی HTTP و در درجهٔ دوم کلاینت Core خود Artemis · SSE · یک ایمیج کانتینر.
[معماری](https://sudoitir.github.io/artemis-studio/reference/architecture) ·
[هر ۸۱ تصمیم معماری](https://sudoitir.github.io/artemis-studio/reference/adr/) (به انگلیسی).

## انتشار نسخه‌ها

هر push به `main` یک نسخهٔ جدید منتشر می‌کند. شمارهٔ نسخه به شکل CalVer (`YYYY.MM.PATCH`) است و روی Docker Hub سه تگ می‌گیرد: `2026.09.3` (ثابت و تغییرناپذیر)، `2026.09` (آخرین نسخهٔ همان ماه) و `dev` (همیشه آخرین نسخه). تا اولین نسخهٔ پایدار، تگ `:latest` نداریم. فایل jar قابل‌اجرا همراه `.sha256` به هر نسخه پیوست می‌شود و یادداشت‌های انتشار از پیام‌های commit ساخته می‌شوند ([`changelog/`](changelog/)).

## نقشهٔ راه

نقشهٔ راه کامل را در [README انگلیسی](README.md#roadmap) ببینید.

## مجوز

[Apache-2.0](LICENSE)؛ همان مجوزی که خود Artemis دارد.

Apache ActiveMQ و Apache ActiveMQ Artemis علامت‌های تجاری بنیاد نرم‌افزار Apache هستند. Artemis Studio پروژه‌ای مستقل است؛ نه ساختهٔ بنیاد نرم‌افزار Apache است، نه مورد تأیید آن و نه وابسته به آن. هر جا از «Artemis» نام برده شده، منظور بروکری است که این ابزار مدیریتش می‌کند.

</div>

---

<div align="center">

اگر در وقتتان صرفه‌جویی کرد، با یک ⭐ به دیده‌شدنش کمک کنید.

</div>
