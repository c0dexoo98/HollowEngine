# HollowEngine

**موتور بازی‌سازی عمومی و همه‌منظوره** — الهام‌گرفته از معماری Unreal Engine، ساخته‌شده با **جاوا خالص** برای **Android (GLES 3.x)** با **گردش‌کار کامل خط فرمان لینوکس** (شبیه Godot). هیچ وابستگی خارجی ندارد.

> General-purpose game engine, Unreal-class architecture, pure Java 17, zero dependencies,
> Android GLES 3.0 runtime + full Linux CLI workflow (`./hollow`).
>
> **v2.0** — فیزیک کامل (حل‌گر ضربه + Joint + خواب اجسام)، IBL کامل، Instancing/Foliage،
> SSAO، Decal، BlendSpace/IK/Notify، EQS + Crowd، Reverb/EQ/Ducking، شبکه (snapshot+interpolation)،
>
> **v2.1** (`hollow-foundation`, Batch 1) — پوسته تعاملی `hollow shell` (REPL + .hvefile)،
> سیستم CVar، کنسول فرمان، CrashHandler، Watchdog، JobQueue، Subsystems، Pool/Arena/RingBuffer/ReleaseQueue،
> MemoryStats، Noise/PCG/Ease/Spline/HalfFloat/Vec4/Rect/Plane، Guid، Prefab، Result.
> چک‌لیست مستر ۵۷۰ قابلیت: `docs/FEATURES.md` (۳۳۰ انجام‌شده، ۲۴۰ در صف).
> World Streaming، Profiler + بیلد تست خودکار.
>
> **v2.2** (تکمیل Batch 1 + Batch 2 رندر) — حافظه: FrameArena خودریست، EntityPool، WeakPool،
> شمارش مرجع AssetDB + تخلیه خودکار، **کدک LZ4 خالص‌جاوا**، MappedFile، Scratch بدون تخصیص،
> MeshStager، LeakTracker. ریاضی: OBB با SAT، Barycentric/مثلث، کروی/استوانه‌ای،
> Poisson-disk/Fibonacci، **کره محصورکننده Welzl**، پک رأس half3+octahedral،
> پروجکشن‌های Reversed-Z/Log-depth/Ortho-off-center، FloatTrack با easing و loop،
> **QuatSpline (squad)**. صحنه: لایه‌های bitmask، متادیتا، وراثت Prefab (زنجیره extends)،
> سوکت استخوان در سطح Entity، کامپوننت فرزند، spawn تعلیقی یک‌فریمی، ایندکس معکوس
> کامپوننت‌ها، Snapshot/Restore (undo ادیتور)، قفل خواندن/نوشتن چندنخی، تبدیل
> fragment↔prefab، رویداد onChildRemoved، عمق/پیمایش ترتیبی، انعکاس props.
> رندر (Batch 2): **پست‌پروسس FX کامل** (جنون، radial blur، DOF، god rays/shafts، CRT،
> outline لبه‌ای، خون روی لنز، distortion، posterize، LUT crossfade، motion blur چرخش
> سر، لرزش دوربین)، LightProbe اتاقی، Bounce light یک‌مرحله‌ای، IES profile،
> رعدوبرق رخدادی، track نور در Sequencer، MaterialInstance، **گراف متریال JSON**
> (bake + validate)، LOD خودکار (metric d-screen).
> تست: `hollow selftest` — ۵۱ تست (همه سبز).

---

## معماری (ماژول‌ها)

| پکیج | نقش | معادل در Unreal |
|---|---|---|
| `com.hollow.core` | JSON، ریاضیات (Vec/Quat/Mat4/AABB)، Curve، RNG قطعی، FileSystem انتزاعی، UiDrawList | Core/Math/Json |
| `com.hollow.scene` | Entity + Component + سلسله‌مراتب Transform + World + Scene(De)Serialize | Actor/Component/World/Level |
| `com.hollow.assets` | AssetDB با کش، **لودر کامل glTF 2.0** (.gltf/.glb/.bin)، فرمت‌های باینری HVM/HVT، AtlasPacker، تولید مثل‌های سه‌بعدی (HvmGen)، WAV، OGG (اندروید) | Content pipeline / Interchange |
| `com.hollow.render` | **رندرر PBR بازتابنده GLES 3.0**: Cook-Torrance GGX، سایه‌ی Directional + PCF، Sky رویه‌ای (ستاره/ماه)، مه حجمی، اسکینینگ GPU (۶۴ مفصل)، اسپرایت billboard، پارتیکل نقطه‌ای، آب، خطوط دیباگ، **پست‌پروسس کامل** (Bloom + ACES + Color Grade + Vignette + Grain + CA + FXAA)، کیفیت‌های LOW→ULTRA | Renderer/Material/PostProcess |
| `com.hollow.anim` | انیمیشن اسکلتی: سمپل، Crossfade بلندینگ، State Machine با شرط/رویداد | Animation Blueprint |
| `com.hollow.physics` | World ایستا/داینامیک، **CharacterController collide-and-slide با step-up**، Raycast (box/sphere/capsule)، تریگرها با رویداد، **NavGrid + A* با smooth** | Chaos (سبک) + Navigation |
| `com.hollow.ai` | **Behavior Tree کامل** (selector/sequence/parallel/inverter/repeat/wait/condition/action)، Blackboard، Perception (مخروط دید + occlusion raycast + شنوایی)، Agent با گشت/تعقیب/جست‌وجو/حمله | AI/BehaviorTree/EQS |
| `com.hollow.audio` | میکسر چندباسه (master/music/sfx/ambient/voice)، صداگذاری سه‌بعدی (equal-power pan + attenuation)، WAV/OGG | MetaSounds (سبک) |
| `com.hollow.ui` | UI رتین‌مد: Panel/Label/Button/Image/Slider/Checkbox/ProgressBar/Toast، انکر لِی‌اوت، رویداد کلیک، **متن فارسی/عربی صحیح روی اندروید** (HarfBuzz خود اندروید) | UMG |
| `com.hollow.vfx` | سیستم پارتیکل CPU: rate/burst/curves (اندازه/رنگ بر عمر)، gravity/drag، additive | Niagara (سبک) |
| `com.hollow.script` | **FlowScript** — گراف رویداد→شرط→اقدام داده‌محور با ۳۰+ اکشن، زبان شرط مینی (`has_item()`, `dist_to() < 5`, `chance(0.3)`...)، EventBus با تاخیر قطعی | Blueprint (داده‌محور) |
| `com.hollow.cine` | **Sequencer** — تایم‌لاین سینماتیک: دوربین/ترنسفورم/انیمیشن/صدا/رویداد/fade/caption با کلیدفریم | Sequencer |
| `com.hollow.game` | GameApp، Bootstrap، InputActions (مپینگ JSON)، SaveSystem (اسلات)، Localization با RTL، GameConfig | GameMode/Enhanced Input/SaveGame |
| `com.hollow.net` | NetMessage/TcpChannel (قاب length-prefix) — اسکلت شبکه | Networking (فاز ۲) |
| `runtime-android` | EngineActivity + GLSurfaceView ES3 + ورودی لمسی دو-منطقه‌ای (استیک مجازی+نگاه)، AudioTrack، BitmapFactory، **متن با شکل‌دهی صحیح**، OGG با MediaCodec | Platform Android |

## گردش‌کار خط فرمان

```bash
./hollow version
./hollow new -n mygame                  # پروژه‌ی کامل بساز
./hollow gen -k sphere -a 1.5 -o projects/mygame/assets/models/ball.hvm
./hollow import -p mygame               # raw/ → assets/ (PNG→HVT با mipmap)
./hollow atlas -p mygame                # بسته‌بندی اسپرایت‌ها
./hollow validate -p mygame             # لینت کامل (JSON، مرجع‌ها، کامپوننت‌ها)
./hollow tree -p mygame                 # درخت صحنه
./hollow run -p mygame --ticks 600 --assert tests.json   # شبیه‌سازی headless + assert
./hollow build apk -p mygame            # خروجی APK امضاشده در dist/
```

## پروژه‌ی بازی

```
projects/<name>/
├── project.json      # عنوان، appId، scene ورودی، کیفیت، جهت
├── input.json        # مپینگ کلید/لمس → action
├── scenes/*.scene.json
├── ai/*.bt.json      # درخت‌های رفتار
├── scripts/*.flow.json  # منطق گیم‌پلی
├── ui/*.ui.json      # رابط کاربری
├── strings/fa.json   # چندزبانه + RTL
├── raw/              # منابع (png/wav/gltf)
└── assets/           # خروجی ایمپورت (HVM/HVT)
```

## ساخت

```bash
export JAVA_HOME=<jdk-21+> ANDROID_SDK_ROOT=<sdk>
./gradlew :runtime-android:assembleRelease   # APK
./gradlew :cli:installDist                   # CLI
```

مستندات بیشتر: `docs/SPEC.md` (قرارداد ماژول‌ها)، `docs/ARCHITECTURE.md`، `docs/FEATURES.md` (ماتریس قابلیت‌ها در برابر Unreal).
