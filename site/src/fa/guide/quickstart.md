---
title: شروع سریع
description: اجرای Artemis Studio و Postgres آن از ایمیج منتشرشده، ثبت اولین کلاستر، و ورود به سیستم.
---

# شروع سریع

Studio دو چیز لازم دارد: یک پایگاه‌دادهٔ PostgreSQL، و دسترسی شبکه به endpointهای مدیریتی بروکرهایتان. کلاسترهای Artemis شما از پیش وجود دارند و از طریق رابط کاربری ثبت می‌شوند — هیچ‌چیز در اینجا بروکری را بالا نمی‌آورد.

::: warning نسخهٔ آلفا
ایمیج‌های منتشرشده buildهای dev پیش از پایدار هستند (`sudoit1/artemis-studio:dev`؛ هنوز `:latest` منتشر نمی‌شود). بین انتشارها تغییرات ناسازگار را انتظار داشته باشید.
:::

## با `just`، از روی یک clone

[`just`](https://github.com/casey/just#packages) همان task runner ای است که این مخزن استفاده می‌کند. اجرای `just` به‌تنهایی همهٔ taskها را گروه‌بندی‌شده فهرست می‌کند.

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up
```

`just up` ابتدا `just setup` را اجرا می‌کند: روی یک checkout تازه، فایل `deploy/compose/.env` را با یک `SECRET_KEY` و `DB_PASSWORD` تصادفی می‌نویسد و `STUDIO_IMAGE` را به تازه‌ترین تگ منتشرشده پین می‌کند. سپس Studio و Postgres را بالا می‌آورد و منتظر می‌ماند تا Studio پاسخ دهد. آدرس <http://localhost:8080> را باز کنید.

بعداً برای جلو بردن پین نسخه، دوباره `just setup` را اجرا کنید.

## بدون `just`

```bash
base=https://raw.githubusercontent.com/sudoitir/artemis-studio/main/deploy/compose
curl -sO "$base/compose.prod.yaml"
curl -s "$base/.env.example" -o .env   # سپس ویرایشش کنید — بخش پیکربندی را ببینید
docker compose -f compose.prod.yaml --env-file .env up -d
```

یا یک کانتینر تنها، روی Postgres ای که از پیش دارید:

```bash
docker run -p 8080:8080 \
  -e ARTEMIS_STUDIO_DB_URL=jdbc:postgresql://db:5432/artemis_studio \
  -e ARTEMIS_STUDIO_DB_USER=artemis_studio \
  -e ARTEMIS_STUDIO_DB_PASSWORD=... \
  -e ARTEMIS_STUDIO_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/artemis-studio:dev
```

## اولین ورود

نام کاربری `admin` است. اولین اجرا روی یک پایگاه‌دادهٔ خالی رمز عبور را می‌سازد و **فقط یک بار** در لاگ کانتینر چاپ می‌کند:

```bash
docker compose -f compose.prod.yaml logs studio | grep -A4 'Created administrator'
```

در اولین ورود مجبور می‌شوید رمز تازه‌ای تعیین کنید. اگر آن رمز اولیه را پیش از تغییرش گم کنید، تنها راه بازیابی، بازنشانی مستقیم همان سطر در Postgres است — هنوز جریانی برای بازیابی رمز وجود ندارد.

## ثبت یک کلاستر

وارد شوید و به **Clusters ← Add** بروید. شما فقط endpoint مدیریتی یک نود seed و اعتبارنامه‌اش را می‌دهید؛ باقی توپولوژی از خود بروکر کشف می‌شود. اعتبارنامه‌ها با `ARTEMIS_STUDIO_SECRET_KEY` رمزنگاری‌شده ذخیره می‌شوند.

اگر قابلیتی در دسترس نباشد — کلاینت Core قابل دسترسی نباشد، یا عملیات مدیریتی‌ای در معرض نباشد — Studio می‌گوید کدام قابلیت است و دقیقاً همان قطعهٔ `broker.xml` را که آن را فعال می‌کند نشان می‌دهد، به‌جای پنهان‌کردن آن قابلیت.

## پشت یک reverse proxy

جریان زندهٔ رابط کاربری، Server-Sent Events روی `GET /api/v1/stream` است و پروکسی جلوی Studio **نباید آن را بافر کند**:

- nginx — روی همان مسیر `proxy_buffering off;`
- Apache — بافر خروجی روی آن مسیر خاموش
- Traefik — بدون تغییر کار می‌کند

بدون این، گراف توپولوژی و جدول صف‌ها فقط با poll پنج‌ثانیه‌ای به‌روز می‌شوند، که شبیه محصولی به‌نظر می‌رسد که به‌زحمت زنده است.

## با بروکرهای یک‌بارمصرف امتحانش کنید

اگر می‌خواهید پیش از وصل‌کردن به هر چیز واقعی آن را در عمل ببینید، مخزن یک dev stack دارد که Studio را از سورس می‌سازد، دو جفت واقعی live/backup آرتمیس بالا می‌آورد و آن‌ها را با ترافیکی پر می‌کند که شبیه یک estate واقعی است — از جمله یک آدرس بدون مصرف‌کننده که عمقش بالا می‌رود، یک انباشت واقعی dead-letter، و یک نود متوقف‌شده.

```bash
just dev-up                          # Postgres، یک جفت آرتمیس، و Studio
ADMIN_PASSWORD=… just demo           # جفت دوم، به‌همراه ترافیک واقع‌نما
```

هیچ‌چیز در آن seed مستقیماً در `metric_sample` یا `queue_snapshot` نمی‌نویسد — ترافیک با CLI خود بروکر تولید و مصرف می‌شود، پس نمودارها اندازه‌گیری‌اند، نه داستان.

## ادامه

- [پیکربندی](/fa/guide/configuration) — هر متغیری که می‌خواند
- [کنسول SQL](/fa/guide/sql-console) — پرسیدن پرسشی که از مرز صف‌ها عبور می‌کند
- [سرور MCP](/fa/guide/mcp) — همان قابلیت‌ها برای یک دستیار
