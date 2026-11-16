# HollowEngine 2 — مشخصات فنی (SPEC)

نسخه: **2.0.0 «genesis»** — بازنویسی کامل از صفر (نسخهٔ قبلی: برنچ `legacy-v20`).

## ۱. لایه‌ها و فایل‌ها

| لایه | فایل‌ها | خطوط (تقریبی) |
|---|---|---|
| core | `Json` (parser+writer با پشتهٔ جداکننده)، `MathX` (Vec3، Mat4 ستونی، perspective، lookAt، Rng xorshift64*) | ~۴۵۰ |
| script | `Lexer` (INDENT/DEDENT، ۱۸ کلیدواژه)، `Parser` (AST + تقدم کامل + متد-کال)، `Interp` (Env زنجیره‌ای، ReturnEx/BreakEx/ContinueEx، ~۲۵ builtin، Native surface) | ~۱٬۴۰۰ |
| scene | `Node` (ترنسفورم سلسله‌مراتبی + snapshot walk)، ۴ نود تایپ‌دار، `Scene` (JSON IO + spawn idempotent)، `ScriptHost` (cache + log-not-throw) | ~۵۰۰ |
| render | `Colors` (پالت+hex)، `Mesh` (OBJ v/vn/f، fan triangulation، cube داخلی)، `Rasterizer` (edge function، z-buffer، Lambert+Blinn، دو-رو)، `Frame` (PNG via ImageIO، fillRatio)، `Renderer` (دوربین فعال+نور+محیط) | ~۶۰۰ |
| editor | `EditorModel` (همهٔ عملیات هدلس)، `EditorApp` (Swing ۵ داک) | ~۴۵۰ |
| cli | `Main` (۷ فرمان)، `Selftest` (۲۶ چک) | ~۵۵۰ |

## ۲. قراردادهای طراحی

1. **بدون وابستگی** — فقط JDK؛ رندر نرم به‌جای GL تا هدلس/تست/دسکتاپ همه از یک مسیر بروند.
2. **قطعیت** — Rng با seed؛ delta ثابت 1/60 در هدلس؛ خروجی PNG تکرارپذیر.
3. **مدل/نما** — ادیتور = نمایش `EditorModel`؛ منطق صفر در Swing.
4. **Log-not-throw** — خطای اسکریپت → Console؛ یک اسکریپت خراب بقیه را نمی‌کُشد.
5. **Snapshot walk** — spawn/remove حین پیمایش ایمن است (signal safety).
6. **یک فرمت** — `.hscene`/`project.json` بین CLI و ادیتور مشترک.

## ۳. زبان HollowScript

مستند کامل: `docs/SCRIPT.md`. خلاصه پیاده‌سازی: بلوک با تورفتگی (INDENT/DEDENT با پشته)، تقدم ۸ سطحی، compound assign، لیست/map literal، متد-کال (`obj.fn(args)` → `Native.hCall` یا متد string/list)، dot-set روی map، خطاها با شماره خط.

## ۴. رندر

خط لوله: `model = node.worldMatrix()` → `vp = proj·view` → transformPoint (تقسیم بر w) → NDC→viewport → raster با edge-function → z-buffer (کوچکتر برنده) → سایه‌زنی: `albedo·(ambient + Lambert·intensity) + Blinn·0.45·intensity` با نرمال دو-رو. دوربین: `lookAt(eye, eye+forward(rot))`، forward برای rot=0 برابر ‎-Z.

## ۵. تست (۱۵۶ چک — همگی سبز)

| سوئیت | چک | پوشش |
|---|---|---|
| VerifyH2Core | ۲۳ | JSON (escape/unicode/خطا)، Mat4 (ترکیب/چرخش/perspective/lookAt)، Rng (قطعیت/کران) |
| VerifyH2Script | ۳۴ | Lexer (تورفتگی/escape/خطا)، Parser (خطا/elif/تک‌خطی)، Interp (تقدم/compound/حلقه‌ها/string/map/list/native/قطعیت/خطاها) |
| VerifyH2Scene | ۲۵ | درخت+ترنسفورم عمیق، reparent، IO با همهٔ نودها+فرزند تودرتو، دوربین فعال، spawn/remove، هر سه قلاب اسکریپت، خطای لاگ‌شده |
| VerifyH2Render | ۲۱ | پالت، OBJ (همهٔ فرم‌ها)، raster/z-buffer دو ترتیب، پشت‌دوربین، شدت نور، رندر صحنه، PNG |
| VerifyH2Editor | ۲۷ | add/dup/rename/delete، اسکیما+setProperty هر نوع، roundtrip، files dock، run با input، manifest، preview |
| Selftest | ۲۶ | invariantهای بین‌لایه‌ای end-to-end |

**CI:** `verify.sh` = build + selftest + ۵ سوئیت + smoke (new→render→run→PNG). قاعدهٔ همان نسخهٔ قبل: **ALL GREEN قبل از هر push.**

## ۶. نقشهٔ راه

ادیتور (gizmo/undo)، رندر (سایه/بافت/کالینگ)، زبان (کلاس/signal/import)، پلتفرم (HTML5/Android GLES).
