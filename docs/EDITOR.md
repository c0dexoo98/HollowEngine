# HollowEngine 2 — ادیتور

ادیتور گرافیکی به سبک گودوت: پنج داک حول یک viewport. اجرا: `hollow editor <project>` (روی سیستم با نمایشگر؛ هدلس پیام راهنما می‌دهد).

```
┌────────────┬──────────────────────────────┬──────────────┐
│ Scene Tree │        Viewport              │  Inspector   │
│ (درخت صحنه)│  (رندر زندهٔ همان rasterizer) │  (جدول پراپ) │
├────────────┴───────────────┬──────────────┴──────────────┤
│ Console (خروجی print اسکریپت‌ها)│ Files (فایل‌های پروژه)     │
└────────────────────────────┴─────────────────────────────┘
```

## داک‌ها

- **Scene Tree** — درخت گره‌ها از root؛ کلیک = انتخاب (Inspector همان لحظه عوض می‌شود).
- **Viewport** — خروجی `Renderer` روی صحنهٔ فعلی؛ هر تغییر مدل باز رندر می‌شود. `Render Frame (PNG)` خروجی ۹۶۰×۵۴۰ را در `frame.png` هم می‌نویسد.
- **Inspector** — جدول `property / value` گرهٔ انتخاب‌شده؛ ویرایش سلول value → `EditorModel.setProperty` → بازسازی داک‌ها. اسکیمای هر نوع: Mesh (`mesh`, `color`)، Camera (`fov`, `active`)، Light (`color`, `intensity`)، Script (`path`) + پراپ‌های پایهٔ ترنسفورم برای همه.
- **Console** — همهٔ `print`های اسکریپت‌ها + رویدادهای ادیتور.
- **Files** — فایل‌های پروژه (مثل داک FileSystem گودوت).

## منوها و ابزارها

- **File:** New Scene / Open Scene / Save Scene / Quit
- **Edit:** Undo / Redo (Ctrl+Z / Ctrl+Y) — snapshot کل صحنه قبل از هر عمل تغییردهنده (تا ۱۰۰ سطح)
- **Scene:** Add mesh/camera/light/script/node (نام یکتا خودکار: `mesh_1`…) / Delete Selected
- **Build:** **Play / Stop (F5)** — حلقهٔ بازی واقعی روی تایمر ۳۰fps: فیزیک + اسکریپت‌ها + ورودی کیبورد (جهت‌ها/WASD/Space → `on_input`)؛ Run 60 Frames (هدلس)؛ Render Frame (PNG)
- **View:** روشن/خاموش کردن دوربین مداری ادیتور

## Viewport تعاملی

- **کلیک** = pick: پرتو از پیکسل دوربین (ray-AABB) → نزدیک‌ترین mesh انتخاب می‌شود
- **دراگ روی گرهٔ انتخاب‌شده** = جابه‌جایی روی صفحهٔ زمین (world-per-pixel از fov و فاصله)
- **دراگ روی فضای خالی** = چرخش دوربین مداری (yaw/pitch)
- **چرخ موس** = zoom

دوربین مداری (spherical حول مبدأ) مستقل از دوربین صحنه است — View می‌توانید برگردید به دوربین صحنه.

## معماری: مدل/نما سخت‌جدا

**همهٔ** عملیات منو از `EditorModel` می‌گذرد — همان کلاسی که سوئیت‌های هدلس پوشش می‌دهند (`VerifyH2Editor` + بخش‌های undo/play/drag در selftest). `EditorApp` فقط Swing است: tree/table/console را از مدل می‌سازد و رویداد را به مدل می‌فرستد. نتیجه: رفتار ادیتور بدون نمایشگر هم کاملاً تست‌شده است؛ هر باگ در مدل با CI گرفته می‌شود، نه روی صفحهٔ کاربر.

فرمت‌ها: صحنه `.hscene` (JSON، سازگار با CLI؛ شامل gravity/fog/shadow_plane_y و dynamic/velocity گره‌ها)؛ پروژه `project.json` (`hproject: 2`).
