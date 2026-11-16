# HollowEngine 2.2 «vale»

موتور بازی‌سازی از صفر — زبان اسکریپت OOP به سبک GDScript، فیزیک، **چند-نور reach‌دار**، سایه، مه، **فونت HUD بیت‌مپ**، وینیت، و ادیتوری با **حالت Play (F5)** که بازی را با کیبورد اجرا می‌کند. بدون وابستگی، خالص جاوا.

> 2.1 موتوری بود که با آن می‌شد بازی ساخت. **2.2 همان موتور با یک بازی واقعی روی آن است**:
> [Hollow Vale](https://github.com/c0dexoo98/horror) — survival horror با کمپین ۷ مأموریتی که
> باتِ قطعی خود بازی آن را سرتاسر تا `ENDING: ESCAPED` می‌بازد. قابلیت‌های این نسل
> (چند-نور، reach، hud()، atan2، فونت، وینیت) از نیازهای همان بازی زاده شدند و همین‌جا تست دارند.

## ستون‌ها

| ستون | چی هست | کجا |
|---|---|---|
| **زبان** | HollowScript — بلوک تورفتگی، **کلاس/extends/self/_init**، لیست/map، متد-کال، native objects، deterministic rand | `engine/src/com/hollow/script/` |
| **فیزیک** | جاذبه، AABB با نیم‌اندازه = scale، resolve کمینه‌محور، restitution، بدنه‌های static/dynamic، رویداد `on_collision` | `engine/src/com/hollow/physics/` |
| **رندرر** | rasterizer نرم: z-buffer، Lambert+Blinn، **تا ۴ نور به‌ازای مش با فالاف reach**، سایهٔ planar، fog، **وینیت**، **فونت بیت‌مپ HUD**، ray picking، PNG | `engine/src/com/hollow/render/` |
| **ادیتور** | ۵ داک + **Undo/Redo (Ctrl+Z/Y)** + دوربین مداری (درگ) + zoom (چرخ موس) + **pick و دراگ گره در viewport** + **Play/Stop با ورودی کیبورد** | `editor/src/com/hollow/editor/` |
| **CLI** | new / run (با فیزیک) / render / script / editor / selftest | `cli/src/com/hollow/cli/` |

## شروع سریع

```bash
./hollow version                    # wrapper که خودش build می‌کند
./hollow new my-game                # پروژهٔ جدید
./hollow render my-game             # frame.png واقعی (با سایه اگر shadow_plane_y باشد)
./hollow run my-game --frames 120   # اجرای هدلس: فیزیک + اسکریپت‌ها
./hollow editor my-game             # ادیتور گرافیکی (روی سیستم خودت) → F5 برای Play
```

## بازی نمونه — `examples/game`

**Orb Runner**: بازیکن (dynamic body) با جاذبه روی زمین می‌ایستد، orb ها با `class Orb` spawn می‌شوند، برخورد AABB رویداد `on_collision` می‌دهد، امتیاز جمع می‌شود، خروجی قطعی است:

```bash
./hollow run examples/game --frames 300
#   orb-runner: collect the orbs! ( 300  frames)
#   collected orb_1 — score: 1  …
#   FINAL SCORE: 6
```

در ادیتور: F5 → بازی با کلیدهای جهت/WASD قابل بازی است.

## زبان در یک نگاه

```python
class Enemy:
    var hp = 10
    func _init(start):
        self.hp = start
    func hit(d):
        self.hp = self.hp - d
        return self.hp

class Boss extends Enemy:
    var armor = 2

var b = Boss.new(50)
b.hit(5)                     # ارث‌بری + override

func on_update(node, scene, delta):
    var hero = scene.find("hero")
    hero.rotation_y = hero.rotation_y + 90 * delta

func on_collision(node, scene, a, b, axis):
    if b.name.contains("orb"):
        scene.remove(b.name)
```

مرجع کامل: [docs/SCRIPT.md](docs/SCRIPT.md)

## تضمین‌ها

- **CI:** `verify.sh` = build + selftest (۳۲) + **۶ سوئیت** (۱۴۰) + smoke — **۱۷۲ چک، ALL GREEN** یا push ممنوع.
- **قطعیت:** seed ثابت، delta ثابت — همان پروژه = همان امتیاز = همان PNG.
- **مدل/نما:** ادیتور فقط نمایش `EditorModel` است؛ هر چیزی که F5 می‌کند، `hollow run` و تست‌ها هم همان را می‌کنند.

## نقشهٔ راه بعدی

1. صدا (WAV synth قطعی) + بافت‌های procedural
2. signal/slot رسمی بین گره‌ها (مثل گودوت) + scene instancing
3. خروجی HTML5 (ترجمهٔ rasterizer به JS) و Android GLES
4. gizmo سه‌محوره در viewport + چند-سند

مستندات: [docs/SPEC.md](docs/SPEC.md) · [docs/SCRIPT.md](docs/SCRIPT.md) · [docs/EDITOR.md](docs/EDITOR.md) · [docs/CLI.md](docs/CLI.md)
