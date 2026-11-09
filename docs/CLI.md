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
hollow selftest                                    # ۹ تست واحد موتور (ریاضی/بلنداسپیس/IK/reverb/EQ/کمپرسور/profiler/wav)
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
