# HollowEngine 2 — CLI

```
hollow new <dir>                       پروژهٔ جدید: scene.hscene + cube.obj + spin.hs + project.json
hollow run <dir> [--frames N]          اجرای هدلس: bind اسکریپت‌ها + N فریم (dt=1/60) + لاگ
hollow render <dir> [--frames N] [--out f.png] [--w 960 --h 540]
                                       N فریم جلو، بعد رندر PNG (exit 3 اگر فریم خالی باشد)
hollow script <file.hs>                اجرای مستقل اسکریپت (main() اگر بود)
hollow editor [dir]                    ادیتور گرافیکی — بدون نمایشگر: پیام + exit 2
hollow selftest                        باتری داخلی (۲۶ چک)
hollow version | help
```

## قراردادها

- مسیرهای نسبی (مثل `path` اسکریپت‌ها) نسبت به **دایرکتوری پروژه** حل می‌شوند، نه CWD.
- `render` خروجی غیرخالی را با fill% و تعداد رنگ گزارش می‌کند؛ فریم تقریباً خالی exit code 3 می‌دهد (برای CI).
- `run` خطاهای اسکریپت را چاپ می‌کند ولی شکست نمی‌خورد — همان رفتار موتور. اجرا از `stepWorld` می‌گذرد: **فیزیک (جاذبه/برخورد) + اسکریپت‌ها** در هر فریم.
- خروجی‌ها قطعی‌اند: همان پروژه + همان seed = همان PNG و همان امتیاز بازی.
- `./hollow …` — wrapper در ریشهٔ مخزن که در صورت نیاز خودش build می‌کند.

## نمونهٔ یک دور کامل

```bash
$ hollow new my-game
created project …/my-game
$ hollow render my-game
wrote …/my-game/frame.png (960x540, fill 6%, 4 colors)
$ hollow run my-game --frames 120
scene: my-game  scripts bound: 1  frames: 120
  spin: started, speed = 90
$ hollow editor my-game        # روی دسکتاپ
```
