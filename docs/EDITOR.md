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

## منوها

- **File:** New Scene / Open Scene / Save Scene / Quit
- **Scene:** Add mesh/camera/light/script/node (نام یکتا خودکار: `mesh_1`…) / Delete Selected
- **Build:** Run 60 Frames (اجرای هدلس با اسکریپت‌ها، خروجی در Console) / Render Frame

## معماری: مدل/نما سخت‌جدا

**همهٔ** عملیات منو از `EditorModel` می‌گذرد — همان کلاسی که ۲۷ چک هدلس دارد (`VerifyH2Editor`). `EditorApp` فقط Swing است: tree/table/console را از مدل می‌سازد و رویداد را به مدل می‌فرستد. نتیجه: رفتار ادیتور بدون نمایشگر هم کاملاً تست‌شده است؛ هر باگ در مدل با CI گرفته می‌شود، نه روی صفحهٔ کاربر.

فرمت‌ها: صحنه `.hscene` (JSON، سازگار با CLI)؛ پروژه `project.json` (`hproject: 2`).
