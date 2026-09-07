---
layout: home
title: Artemis Studio
titleTemplate: یک کنسول برای همهٔ کلاسترهای Artemis
head:
  - - meta
    - name: description
      content: مدیریت و مشاهده‌پذیری در سطح کلاستر برای Apache ActiveMQ Artemis — توپولوژی زندهٔ live/backup، صف‌ها در همهٔ نودها، عملیات امن روی پیام‌ها، ردیابی request-reply و SQL روی پیام‌ها.

hero:
  name: Artemis Studio
  text: یک کنسول برای همهٔ کلاسترهای Artemis
  tagline: توپولوژی زنده، صف‌ها در همهٔ نودها، عملیات امن روی پیام‌ها و SQL روی پیام‌ها — برای هر کلاستر Apache ActiveMQ Artemis که اجرا می‌کنید، از یک نمونهٔ واحد.
  image:
    src: /img/topology.png
    alt: توپولوژی زندهٔ live/backup در یک کلاستر
  actions:
    - theme: brand
      text: شروع سریع
      link: /fa/guide/quickstart
    - theme: alt
      text: این چیست
      link: /fa/guide/
    - theme: alt
      text: GitHub
      link: https://github.com/sudoitir/artemis-studio

features:
  - title: کل کلاستر، نه یک بروکر
    details: جفت‌های live/backup، وضعیت replication و محور NodeID مشترک، همه روی یک بوم. نقش HA از هر نود poll می‌شود و هرگز از فایل پیکربندی خوانده نمی‌شود — دو نود live در یک جفت یک هشدار split-brain است، نه غافلگیری ساعت سه بامداد.
    link: /fa/guide/
  - title: همهٔ صف‌ها، همهٔ نودها، یک جدول
    details: صف‌ها، آدرس‌ها، مصرف‌کننده‌ها، نشست‌ها، اتصال‌ها و تولیدکننده‌ها در سراسر estate، در یک جدول مجازی‌سازی‌شده که روی SSE به‌روز می‌شود. بر اساس عمق مرتب کنید تا بدترین چیز کلاستر سطر اول باشد.
    link: /fa/guide/
  - title: SQL روی پیام‌هایتان
    details: «سفارش ۴۴۷۱ کجا رفت؟» یک کوئری است روی همهٔ صف‌های کلاستر — از جمله بدنهٔ پیام، که یک JMS selector اصلاً نمی‌بیند. هزینهٔ کوئری پیش از اجرا نشان داده می‌شود.
    link: /fa/guide/sql-console
  - title: عملیات مخرب که می‌شود به آن اعتماد کرد
    details: هر فراخوانی تغییردهنده ‎?dryRun=true‎ می‌پذیرد و تعداد متأثرشده را بدون انجام کار برمی‌گرداند. purge و delete نیازمند تایپ‌کردن نام صف هستند. همه‌چیز در همان تراکنشِ فرمان ممیزی می‌شود.
    link: /fa/guide/
  - title: ردیابی request-reply
    details: هر درخواست به پاسخش، در سراسر آدرس‌ها و نودها، متناظر می‌شود؛ همراه با آمار تأخیر و timeout در برابر انتظاراتی که خودتان اعلام می‌کنید. ساعت بروکرها روی ساعت Studio نرمال‌سازی و اختلاف آن افشا می‌شود.
    link: /fa/guide/
  - title: یک سرور MCP با همان دسترسی‌ها
    details: از یک دستیار بپرسید چرا ORDERS.DLQ انباشته شده و بگذارید روی کلاسترهای واقعی‌تان پاسخ دهد — با حدود دوازده ابزار مبتنی بر قصد، زیر همان مجوزها و همان مسیر ممیزی یک کاربر انسانی.
    link: /fa/guide/mcp
---

## ببینیدش که کار می‌کند

![Artemis Studio: توپولوژی، جدول صف‌ها در همهٔ نودها، و پیامی که به صف dead-letter رفته است](/img/demo.gif)

یک نشست واقعی روی یک جفت کلاستر واقعی: توپولوژی، جدول صف‌ها در سراسر نودها، و پیامی که سر از صف dead-letter درآورده است.

## با یک فرمان نصبش کنید

```bash
git clone https://github.com/sudoitir/artemis-studio && cd artemis-studio
just up          # Studio و Postgres، با کلیدهای تولیدشده و پین‌شده به آخرین انتشار
```

بروکرهای شما از پیش وجود دارند — Studio آن‌ها را ثبت می‌کند، اجرا نمی‌کند. جز فعال‌کردن endpointهای مدیریتی که تقریباً حتماً همین حالا هم دارید، نیازی به بازنویسی `broker.xml` نیست. [شروع سریع کامل ←](/fa/guide/quickstart)

::: warning نسخهٔ آلفا
در حال توسعهٔ فعال و هنوز کامل نیست. ایمیج‌های منتشرشده buildهای dev پیش از پایدار هستند (`sudoit1/artemis-studio:dev`، هنوز بدون `:latest`). تغییرات ناسازگار را انتظار داشته باشید.
:::
