# HollowEngine CLI Reference

```
hollow version                              نسخه موتور
hollow new -n <name>                        پروژه جدید در projects/<name>
hollow gen -k <kind> -o <file.hvm> [opts]   تولید مش: box|plane|sphere|capsule|cylinder
        -a (ابعاد1) -h (ارتفاع) -seg (تقسیمات)
hollow import -p <project>                  raw/ → assets/ (PNG→HVT با mipmap، WAV کپی، manifest.json)
hollow atlas -p <project>                   raw/sprites/*.png → assets/atlas/main.hvt + main.atlas
hollow validate -p <project>                لینت: JSON، مرجع asset، درخت BT، entryScene
hollow tree -p <project> [-s <scene>]       درخت موجودیت‌های صحنه
hollow run -p <project> --ticks N --assert <tests.json>
                                            شبیه‌سازی headless قطعی + گزاره‌ها (exit code)
hollow build apk -p <project>               استیج + Gradle + امضا → dist/<name>-<ver>.apk
hollow clean -p <project>                   پاک‌سازی خروجی‌های ایمپورت
```

## گزاره‌های تست headless (`tests.json`)
```json
[
  {"type":"entity_exists","name":"Gatekeeper"},
  {"type":"entity_count_at_least","value":12},
  {"type":"colliders_at_least","value":6},
  {"type":"var","key":"health","op":">=","value":100},
  {"type":"var_exists","key":"door_open"},
  {"type":"log_contains","text":"door opens"},
  {"type":"no_error_logs"}
]
```

## متغیرهای محیطی
- `JAVA_HOME` — JDK 21+ (برای keytool/gradle)
- `ANDROID_SDK_ROOT` — مسیر SDK (پیش‌فرض /home/z/android-sdk)
- `HE_GRADLE_BIN` — باینری gradle سفارشی (در سندباکس‌هایی که wrapper کند است)
- `HE_GRADLE_ARGS` — آرگومان‌های اضافه برای gradle

## فرمان‌های v2.0

```bash
hollow selftest                                    # ۸۰+ تست واحد موتور (v2.3: برخورد/فیزیک/صدا/انیمیشن)
hollow pose -p proj -c walk -t 0.5                 # دامپ JSON پوز انیمیشن در زمان t (ANI-025)
hollow profile -p proj --ticks 600 --csv out.csv   # پروفایلر زونه‌ای (anim/ai/physics/...) + خروجی CSV
hollow bench -p proj --bodies 150 --ticks 600      # بنچمارک فیزیک با اجسام داینامیک
hollow net test                                    # loopback کامل سرور/کلاینت (WELCOME/snapshot/interpolation)
hollow audio-render -p proj --seconds 5 --out mix.wav   # میکس آفلاین با کل زنجیره DSP → WAV
hollow eqs -p proj --query cover                   # اجرای کوئری EQS از صحنه ورودی
hollow run -p proj --scene scenes/other.json       # اجرای headless با صحنه دلخواه
```

### اسکیماهای JSON جدید

- `anim/*.blendspace.json` — Blend Space 1D/2D (axis1/axis2 + samples)
- `ai/*.eqs.json` — کوئری EQS (generator + tests با weight)
- کامپوننت‌های صحنه: `DistanceJoint` / `SpringJoint` / `HingeJoint` / `Attachment` / `FootIK` / `Foliage` / `Decal` / `StreamingVolume`
- `ParticleEmitter`: `trail`, `trailMinDist`, `trailSpacing`, `deathEffect`, `collide`, `collideKill`, `collideBounce`
- `RendererConfig` (project.json quality): `ibl`, `ssao`, `decals`, `instancing` + `ssaoSamples/Radius/Strength`
- انواع assert در `hollow run --assert`: `body_at_rest`, `body_below_y`, `bodies_asleep_at_least`, `eqs_found`, `cells_loaded`, `event_seen`

## پوسته تعاملی `hollow shell` — v2.1 (Batch 1)

REPL موتور به سبک Godot/Linux: مدیریت یک سشن headless از ترمینال.
سه حالت اجرا:

```bash
hollow shell                    # تعاملی (hollow> پرامپت، خروج = exit/quit/Ctrl-D)
hollow shell -c "cmd1; cmd2"    # یک‌خطی غیرتعاملی
hollow shell -f session.hvefile # اجرای دسته‌ای اسکریپت (# = کامنت)
```

### دستورهای سشن

```
help [cmd]                 فهرست دستورها / راهنمای یکی
echo, version, history     پایه
clear (cls)                پاک‌سازی تاریخچه و لاگ

engine start|stop|pause|resume|status
engine step <ticks>        قدم به قدم شبیه‌سازی (ضدیت‌پذیر)
engine speed <x>           timeScale

world new <name>|load <file>|unload|list|info|save <file>
actor spawn <name>|list|inspect <id|name>|kill|move <id> x y z|tag|find
component list|add <id> <Type> [json]|remove|set <id> <Type> <json>|get
prefab load <file>|save <id> <file>|spawn <name> [x y z]

cvar list [filter]|get <name>|set <name> <value>|save <file>|load <file>
                           # نام ناشناس در set به‌صورت Quake-style ساخته می‌شود
                           # داخل سشن هر CVar ثبت‌شده مثل دستور کار می‌کند: fov 95
mem | subsys | gc          تله‌متری حافظه / جدول ساب‌سیستم‌ها / GC
guid                       شناسه پایدار جدید
batch <file.hvefile>       اجرای اسکریپت از داخل سشن
screenshot [--out f.json]  اسنپ‌شات JSON وضعیت دنیا (پیکسلی = Batch 2)
wait <ticks>               قدم زدن تا رسیدن به تیک هدف
```

### نمونه سشن

```
$ hollow shell
hollow> world new demo
created empty world 'demo'
hollow> engine start
engine started (world 'demo', 0 entities)
hollow> actor spawn guard
spawned guard#1
hollow> actor tag 1 enemy
guard#1 tag='enemy'
hollow> cvar set fov 95
fov = 95.0
hollow> engine step 30
stepped 30 ticks -> tick 30, time 0.50s
hollow> actor inspect 1
guard#1  ...
```

نمونه `.hvefile` (اسکریپت سشن):

```
# smoke test دنیا
world load scenes/entry.json
engine start
engine step 300
mem
actor list
engine stop
```

## فرمان‌های v2.5

```bash
# توزیع (EXT)
hollow export itch -p mygame [--apk dist/app.apk]   # بسته itch.io + قالب صفحه
hollow export ci -p mygame                          # قالب GitHub Actions
hollow export blender [--out tools/blender]         # افزونه خروجی .hvm برای Blender
hollow export jar                                   # بسته single-jar در dist/hollow.jar

# اسکریپت (SCR/EXT-008)
hollow lint scripts/demo.flow.json --strict         # lint با کدهای خطا
hollow lsp                                          # سرور LSP روی stdio (VSCode)
hollow debug scripts/demo.flow.json --bp 3 --step   # دیباگر breakpoint/step
```

دیباگر اسکریپت خروجی نمونه:

```
$ hollow debug scripts/demo.flow.json --bp 2
script: scripts/demo.flow.json steps=7 dbg[script=scripts/demo.flow.json step=-1 RUN bps=1 hits=0]
  0 run  {"do":"set_var","key":"hp","value":10}
  1 run  {"do":"add_var","key":"hp","delta":-3}
  2 BREAK {"do":"emit","event":"low_hp"}
dbg[script=scripts/demo.flow.json step=2 BREAK bps=1 hits=1]
```

## رگرسیون کامل CI — `bash verify.sh`

یک فرمان، کل سلامت موتور (بیلد + selftest + همه سوئیت‌های رگرسیون + bench). خروجی باید **`VERIFY: ALL GREEN`** باشد؛ هر FAIL قبل از push باید فیکس شود.

```
== build (engine + cli)                      OK  build
== selftest                                  OK  selftest (253 checks)
== regression suites                         VerifyMath2 (8) / VerifyInfra (9) / VerifyAudio (8) /
                                             VerifyPhysics (8) / VerifyNet (8) / VerifyNav (10) /
                                             VerifyGameplay (41) / VerifyPlatform (22) /
                                             VerifyAaa (54, v4) / VerifyV5 (78) / VerifyV6 (86) /
                                             VerifyV7 (87, v7) / VerifyV8 (67, v8) /
                                             VerifyV9 (67, v9) / VerifyV10 (64, v10) /
                                             VerifyV11 (61, v11) / VerifyV12 (58, v12) /
                                             VerifyV13 (54, v13) / VerifyV14 (54, v14) /
                                             VerifyV15 (65, v15) / VerifyV16 (66, v16) /
                                             VerifyV17 (61, v17) / VerifyV18 (75, v18) /
                                             VerifyV19 (71, v19) / BenchCore
== demo asserts                              demo-fps (5) / engine-tests (8)
```

هر نسخهٔ جدید: سوئیت خودش را به `verify/VerifyV<N>.java` اضافه کن، نامش را در لیست `verify.sh` ثبت کن،
و مستندات (`README.md` + `docs/FEATURES.md` پیوست) را همگام با نسخه به‌روز کن.
