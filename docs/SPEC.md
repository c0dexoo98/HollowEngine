# HollowEngine 2 — مشخصات فنی (SPEC)

نسخه: **2.0.0 «genesis»** — بازنویسی کامل از صفر (نسخهٔ قبلی: برنچ `legacy-v20`).

## ۱. لایه‌ها و فایل‌ها

| لایه | فایل‌ها | خطوط (تقریبی) |
|---|---|---|
| core | `Json` (parser+writer با پشتهٔ جداکننده)، `MathX` (Vec3، Mat4 ستونی، perspective، lookAt، Rng xorshift64*) | ~۴۵۰ |
| script | `Lexer` (INDENT/DEDENT، ۲۱ کلیدواژه)، `Parser` (AST + تقدم کامل + متد-کال + **ClassDef**)، `Interp` (Env زنجیره‌ای، **ScriptObject + ارث‌بری + _init**، ~۲۸ builtin، Native surface) | ~۱٬۶۰۰ |
| scene | `Node` (ترنسفورم سلسله‌مراتبی + snapshot walk + **بدنهٔ فیزیکی**)، ۴ نود تایپ‌دار، `Scene` (JSON IO + spawn idempotent + gravity/fog/shadowPlane)، `ScriptHost` (**stepWorld**: فیزیک→برخورد→اسکریپت) | ~۶۰۰ |
| physics | `Physics` (جاذبه، AABB با نیم‌اندازه=scale، resolve کمینه‌محور دو-pass، restitution، فیلتر بدنه‌های solid، rayBox برای pick) | ~۱۲۰ |
| render | `Colors`، `Mesh` (OBJ)، `Rasterizer` (z-buffer، Lambert+Blinn، **fog فاصله‌ای**، **سایهٔ planar با bias**، **cameraRay**)، `Frame` (PNG)، `Renderer` (+pick) | ~۷۰۰ |
| editor | `EditorModel` (**undo/redo snapshot**، **دوربین مداری**، **pick/drag**، **Play mode**)، `EditorApp` (۵ داک + F5 + کیبورد) | ~۶۵۰ |
| cli | `Main` (۷ فرمان)، `Selftest` (۳۲ چک) | ~۷۰۰ |

## ۲. قراردادهای طراحی

1. **بدون وابستگی** — فقط JDK؛ رندر نرم به‌جای GL تا هدلس/تست/دسکتاپ همه از یک مسیر بروند.
2. **قطعیت** — Rng با seed؛ delta ثابت؛ خروجی PNG و امتیاز بازی تکرارپذیر.
3. **مدل/نما** — ادیتور = نمایش `EditorModel`؛ منطق صفر در Swing.
4. **Log-not-throw** — خطای اسکریپت → Console؛ یک اسکریپت خراب بقیه را نمی‌کُشد.
5. **Snapshot walk** — spawn/remove حین پیمایش ایمن (signal safety).
6. **یک فرمت** — `.hscene`/`project.json` بین CLI و ادیتور مشترک.
7. **یک مسیر شبیه‌سازی** — `ScriptHost.stepWorld` (فیزیک→برخورد→اسکریپت) را `hollow run`، حالت Play ادیتور و تست‌ها مشترک استفاده می‌کنند.

## ۳. زبان HollowScript

مستند کامل: `docs/SCRIPT.md`. تورفتگی (پشته INDENT/DEDENT)، تقدم ۸ سطحی، compound assign، لیست/map literal، متد-کال، dot-set روی map، **کلاس‌ها**: `class/extends/self/_init`، فیلدهای ریشه‌به‌برگ، جست‌وجوی متد در زنجیرهٔ ارث، declare-on-set، `TypeName.new(args)`، خطاها با شماره خط.

## ۴. رندر

خط لوله: `model = node.worldMatrix()` → `vp = proj·view` → تقسیم بر w → NDC→viewport → raster با edge-function → z-buffer → سایه‌زنی: **چند-نور** (تا ۴ نورِ نزدیک به‌ازای مش؛ رنگ هر نور روی albedo؛ specular از قوی‌ترین؛ **فالاف reach** به‌ازای مثلث از مبدأ نور — نور نقطه‌ای برای فانوس/چراغ‌قوه) → **fog** (FOG_NEAR 12/FOG_FAR 50) → **وینیت** سطح صحنه. **سایهٔ planar** از نور اول با depth-bias. **فونت ۵×۷** دست‌نویس + کانال `hud()` اسکریپت‌ها → drawHud با سایهٔ ۱px روی فریم. **pick**: پرتو از پیکسل (basis دوربین) → rayBox (slab).

## ۵. فیزیک

بدنه‌ها: `dynamic` گره‌ها حرکت می‌کنند؛ فقط mesh و node (مرئی، غیر-root؛ **props ریزتر از 0.45 غیر-solid** — آیتم‌های قابل‌برداشت مانع نیستند) solid اند — دوربین/نور/اسکریپت جعبهٔ فیزیکی ندارند. نیم‌اندازه = scale (مکعب داخلی ±1×scale). حل: جاذبه → انتگرال → دو pass resolve کمینه‌محور؛ دو بدنهٔ dynamic نصف‌نصف؛ `bounciness` روی مؤلفهٔ برخوردی؛ سرعت کم (<0.5) صفر می‌شود (استراحت). رویدادها به ترتیب درخت — قطعی.

## ۶. تست (۱۸۰ چک — همگی سبز)

| سوئیت | چک | پوشش |
|---|---|---|
| VerifyH2Core | ۲۳ | JSON، Mat4، Rng (قطعیت/کران) |
| VerifyH2Script | ۳۴ | Lexer/Parser/Interp کامل |
| VerifyH2Scene | ۲۵ | درخت/ترنسفورم/IO/قلاب‌ها |
| VerifyH2Render | ۲۹ | OBJ/raster/z-buffer/نور/PNG + فونت (ترتیب بیت‌ها)، وینیت، چند-نور+reach، کانال hud |
| VerifyH2Editor | ۲۷ | عملیات مدل/اسکیما/roundtrip |
| VerifyH2Physics | ۱۰ | جاذبه/فرود دقیق/restitution/کلامپ افقی/تقسیم push/فیلتر solid/on_collision/pick |
| Selftest | ۳۲ | بین‌لایه‌ای: کلاس‌ها، فیزیک e2e، undo/redo، play+drag+orbit، سایه |

**CI:** `verify.sh` = build + selftest + **۶ سوئیت** + smoke (new→render→run→PNG). **ALL GREEN قبل از هر push.**

## ۷. نقشهٔ راه

صدا (WAV synth قطعی) + بافت procedural؛ signal/slot + scene instancing؛ HTML5/Android GLES؛ gizmo سه‌محوره + چند-سند.
