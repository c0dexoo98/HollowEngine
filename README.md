# HollowEngine 2 «genesis»

موتور بازی‌سازی از صفر — با **زبان اسکریپت** به سبک GDScript، **ادیتور داک‌محور** به سبک گودوت/آنریل و **رندرر سه‌بعدی واقعی** (بدون وابستگی، خالص جاوا).

> نسخهٔ قبلی (v20، ۱۵۳ سیستم شبیه‌سازی) روی برنچ `legacy-v20` حفظ شده و با `git checkout legacy-v20` در دسترس است. این مخزن حالا موتور نسل دوم است: کوچک‌تر، واقعی‌تر، قابل‌اجرا.

## سه ستون

| ستون | چی هست | کجا |
|---|---|---|
| **زبان** | HollowScript — بلوک با تورفتگی، `func`، `var`، لیست/map، متد-کال، native objects، deterministic `rand` | `engine/src/com/hollow/script/` |
| **ادیتور** | پنجرهٔ Swing با داک‌های Scene Tree / Viewport / Inspector / Console / Files — همهٔ عملیات از `EditorModel` می‌گذرد (هدلس‌تست‌پذیر) | `editor/src/com/hollow/editor/` |
| **رندرر** | rasterizer نرم: model/view/projection، perspective divide، z-buffer، Lambert + Blinn specular، خروجی PNG — همان کد برای CLI و ادیتور و تست‌ها | `engine/src/com/hollow/render/` |

## شروع سریع

```bash
bash verify.sh                              # CI کامل — باید ALL GREEN بماند
hollow new my-game                          # ساخت پروژه
hollow render my-game                       # frame.png واقعی (960×540)
hollow run my-game --frames 120             # اجرای هدلس اسکریپت‌ها
hollow editor my-game                       # ادیتور گرافیکی (روی سیستم خودت، با نمایشگر)
```

(در مخزن: `java -cp build-out/cli:build-out/engine:build-out/editor com.hollow.cli.Main …` یا با build.sh مسیر `hollow` را بسازید.)

## یک بازی واقعی در ۱۵ خط — `player.hs`

```python
var spin_speed = 90

func on_start(node, scene):
    print("demo ready")

func on_update(node, scene, delta):
    var hero = scene.find("hero")
    hero.rotation_y = hero.rotation_y + spin_speed * delta

func on_input(node, scene, key):
    if key == "left":
        hero = scene.find("hero")
        hero.position_x = hero.position_x - 1
```

کاملش در `examples/demo/` — هر دو دستور `run` و `render` روی آن سبز است.

## معماری

```
engine/  core    Json (خواندن/نوشتن)، MathX (Vec3/Mat4/perspective/lookAt/Rng قطعی)
         script  Lexer → Parser (AST) → Interp (محیط‌ها، builtins، Native surface)
         scene   Node (ترنسفورم سلسله‌مراتبی) + MeshNode/CameraNode/LightNode/ScriptNode
                 Scene (JSON .hscene) + ScriptHost (on_start/on_update/on_input)
         render  Mesh (OBJ loader) + Rasterizer (z-buffer) + Frame (PNG) + Renderer
editor/  EditorModel (حالت ادیتور — ۲۷ چک هدلس) + EditorApp (Swing — فقط نمایش)
cli/     new / run / render / script / editor / selftest + Selftest battery
verify/  VerifyH2Core / Script / Scene / Render / Editor (۱۳۰ چک)
```

## تضمین‌ها

- **CI:** `verify.sh` = build + selftest (۲۶) + ۵ سوئیت (۱۳۰) + smoke lifecycle (new→render→run→PNG) — **ALL GREEN** یا push ممنوع.
- **Determinism:** همهٔ رندرها و اسکریپت‌ها با seed قطعی؛ خروجی `render` تکرارپذیر است.
- **دو سمت یک مدل:** ادیتور فقط `EditorModel` را نمایش می‌دهد؛ هر چیزی که ادیتور می‌کند با CLI و تست‌ها هم همان است.

## نقشهٔ راه بعدی (برای رسیدن به تجربهٔ گودوت/آنریل)

1. **Edیتور:** drag در viewport، gizmo ترنسفورم، undo/redo، چند-سنده
2. **رندرر:** سایه (shadow map)، بافت، پس‌زمینهٔ چندلایه، بک‌فیس کالینگ اختیاری
3. **زبان:** کلاس/وراثت، signal/slot مثل گودوت، import ماژول
4. **پلتفرم:** خروجی HTML5 (رندرر به JS ترجمه شود) و Android GLES backend

مستندات: [docs/SPEC.md](docs/SPEC.md) · [docs/SCRIPT.md](docs/SCRIPT.md) · [docs/EDITOR.md](docs/EDITOR.md) · [docs/CLI.md](docs/CLI.md)
