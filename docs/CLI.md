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
