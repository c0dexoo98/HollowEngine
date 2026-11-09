# HollowEngine — Master Feature Checklist (۵۷۰ قابلیت)

چک‌لیست مستر توسعه موتور. هر قابلیت شناسه یکتا دارد (`CAT-NNN`) و در همین فایل 
با `- [x]` (پیاده‌سازی‌شده و تست‌شده) یا `- [ ]` (در صف) ردیابی می‌شود.

- **نسخه فعلی موتور:** 2.1.0 (`hollow-foundation`)
- **ساختار نوبت‌ها:** Batch 1 هسته/حافظه/ریاضی/شیء + CLI → Batch 2 رندر → Batch 3 فیزیک/صدا/انیمیشن → Batch 4 AI/UI/ورودی → Batch 5 شبکه/سیو/بومی‌سازی/دارایی → Batch 6 VFX/محیط/دیباگ/VR

| دسته | شناسه‌ها | انجام‌شده | کل | نوبت |
|---|---|---|---|---|
| CORE | CORE-001…CORE-025 | 25 | 25 | B1 |
| MEM | MEM-001…MEM-020 | 11 | 20 | B1 |
| MATH | MATH-001…MATH-030 | 21 | 30 | B1 |
| OBJ | OBJ-001…OBJ-030 | 16 | 30 | B1 |
| WLD | WLD-001…WLD-025 | 9 | 25 | B4 |
| RND | RND-001…RND-035 | 27 | 35 | B2 |
| LGT | LGT-001…LGT-020 | 12 | 20 | B2 |
| MAT | MAT-001…MAT-020 | 12 | 20 | B2 |
| PST | PST-001…PST-020 | 8 | 20 | B2 |
| PHY | PHY-001…PHY-035 | 18 | 35 | B3 |
| COL | COL-001…COL-025 | 10 | 25 | B3 |
| AUD | AUD-001…AUD-030 | 16 | 30 | B3 |
| ANI | ANI-001…ANI-025 | 12 | 25 | B3 |
| AI | AI-001…AI-030 | 10 | 30 | B4 |
| UI | UI-001…UI-020 | 8 | 20 | B4 |
| INP | INP-001…INP-015 | 6 | 15 | B4 |
| NET | NET-001…NET-020 | 5 | 20 | B5 |
| SAV | SAV-001…SAV-015 | 4 | 15 | B5 |
| LOC | LOC-001…LOC-010 | 4 | 10 | B5 |
| AST | AST-001…AST-012 | 9 | 12 | B5 |
| SEQ | SEQ-001…SEQ-008 | 4 | 8 | B5 |
| VFX | VFX-001…VFX-025 | 8 | 25 | B6 |
| ENV | ENV-001…ENV-020 | 5 | 20 | B6 |
| DBG | DBG-001…DBG-020 | 7 | 20 | B6 |
| SCR | SCR-001…SCR-015 | 6 | 15 | B6 |
| VR | VR-001…VR-010 | 0 | 10 | B6 |
| EXT | EXT-001…EXT-010 | 4 | 10 | B6 |
| **جمع** | ۳۰ دسته | **277** | **570** | ۶ نوبت |

---

## 1. CORE — هسته موتور (25/25)

_چرخه اصلی، زمان، سرویس‌ها، CVar، خرابی و ابزارهای زیرساختی_

- [x] **CORE-001** — حلقه اصلی گام ثابت (60Hz) با جدا شدن منطق از رندر
- [x] **CORE-002** — ساعت دقیق نانوثانیه: delta، timeScale، انباشتگر ضد spiral-of-death
- [x] **CORE-003** — چرخه عمر pause/resume اپلیکیشن اندروید
- [x] **CORE-004** — EventBus منتشر/مشترک برای رویدادهای موتور و بازی
- [x] **CORE-005** — لاگ سطح‌بندی‌شده (DEBUG/INFO/WARN/ERROR) با برچسب ماژول
- [x] **CORE-006** — Profiler نواحی CPU با خروجی CSV و جمع‌بندی per-frame
- [x] **CORE-007** — پارس آرگومان خط فرمان برای همه ابزارهای CLI
- [x] **CORE-008** — سیستم CVar (متغیر کنسول int/float/bool/string) با clamp و listener
- [x] **CORE-009** — ذخیره/بارگذاری CVar ها از فایل config + ردیابی dirty
- [x] **CORE-010** — ServiceLocator برای ثبت/واکشی سرویس‌های سراسری
- [x] **CORE-011** — init/shutdown مرتب سرویس‌ها با اولویت و ایزوله‌سازی خطا (Subsystems)
- [x] **CORE-012** — CrashHandler: گرفتن استثنای مهلک + نوشتن گزارش خرابی timestamp دار
- [x] **CORE-013** — نسخه‌گذاری ساختاریافته موتور (major/minor/patch/codename + بنر)
- [x] **CORE-014** — پشتیبانی دو پلتفرم Android/Desktop از هسته مشترک جاوا
- [x] **CORE-015** — رجیستری ساب‌سیستم‌ها با ترتیب وابستگی و status table
- [x] **CORE-016** — الگوی سینگل‌تون thread-safe در سرویس‌های سراسری
- [x] **CORE-017** — JobQueue صف وظایف با حالت deterministic (اجرای inline)
- [x] **CORE-018** — بودجه زمانی فریم + maxFrameTime ضد قفل‌شدگی
- [x] **CORE-019** — محدودکننده FPS / vsync نرم در حلقه رندر
- [x] **CORE-020** — حالت پس‌زمینه: توقف رندر/صدا هنگام loss focus
- [x] **CORE-021** — Config لایه‌ای: پیش‌فرض موتور → پروژه → کاربر
- [x] **CORE-022** — رویدادهای چرخه عمر (onStart/onPause/onResume/onDestroy) برای کامپوننت‌ها
- [x] **CORE-023** — Result<T> مدیریت خطای صریح بدون استثنا در مسیر داغ
- [x] **CORE-024** — متریک‌های سراسری: فریم، GC، هیپ، نخ‌ها (MemoryStats)
- [x] **CORE-025** — Watchdog فریم: تشخیص گیرکردن حلقه اصلی + thread dump

## 2. MEM — حافظه و تخصیص (11/20)

_استخرها، آرنا، بافر حلقه‌ای، تله‌متری و صف آزادسازی_

- [x] **MEM-001** — Pool<T> عمومی: obtain/free با reset و آمار hits/peak
- [x] **MEM-002** — ArenaAllocator با mark/rewind و دسترسی int/float مستقیم
- [x] **MEM-003** — RingBuffer دایره‌ای با حالت overwrite و آمار drop
- [x] **MEM-004** — استخر بافرهای اولیه float[]/byte[] با bucket توان ۲
- [x] **MEM-005** — ردیابی عمر اشیاء GPU (GlObjects) با شمارش باقی‌مانده
- [x] **MEM-006** — گزارش انسانی حافظه: heap/non-heap/threads/pools
- [x] **MEM-007** — شمارنده تخصیص موتور (tracked alloc bytes) با ریست per-frame
- [x] **MEM-008** — سنجش فشار GC (نرخ کالکشن هموارشده 0..1)
- [x] **MEM-009** — صف آزادسازی مؤخر ReleaseQueue برای هندل‌های بومی/GPU
- [x] **MEM-010** — رابط Tracked برای اتصال آمار حافظه هر ساب‌سیستم
- [x] **MEM-011** — مشاور سلامت حافظه (OK/WARN/HIGH) برای کیفیت‌بندی خودکار
- [x] **MEM-012** — بازنشانی خودکار Arena در پایان هر فریم داخل حلقه اصلی
- [x] **MEM-013** — استخر Entity/Component برای اسپاون حجیم بدون GC
- [x] **MEM-014** — کش اشیاء weak (WeakPool) برای دیکد کش دارایی‌ها
- [x] **MEM-015** — شمارش مرجع دارایی‌ها در AssetDB + آزادسازی خودکار
- [x] **MEM-016** — فشرده‌سازی بافرهای موقت (LZ4 جاوا خالص) برای اسنپ‌شات
- [x] **MEM-017** — I/O نگاشت‌حافظه (mmap) برای فایل‌های دارایی بزرگ
- [x] **MEM-018** — مسیرهای تخصیص‌صفر (allocation-free) در حلقه رندر
- [x] **MEM-019** — استیجینگ مش با Arena برای آپلود GPU یک‌مرحله‌ای
- [x] **MEM-020** — گزارش نشتی: اشیاء ردیابی‌شده آزادنشده هنگام shutdown

## 3. MATH — ریاضیات (21/30)

_بردارها، چهارک، ماتریس، نویز، منحنی، easing و تصادف قطعی_

- [x] **MATH-001** — Vec2 با عملیات کامل (lerp/dist/rotate/norm)
- [x] **MATH-002** — Vec3 با dot/cross/lerp/dist و کش بردار محورها
- [x] **MATH-003** — Vec4 چهارمؤلفه‌ای برای رنگ/شیدر/هم‌نهشتی
- [x] **MATH-004** — Quat کامل: fromEuler/axis-angle/slerp/invert/rotate
- [x] **MATH-005** — Mat4 با TRS، معکوس، transformPoint/Vector، normalMatrix
- [x] **MATH-006** — Color با فرمت‌های float/byte/lerp/HSL
- [x] **MATH-007** — AABB با expand/overlap/contains/center/extents/radius
- [x] **MATH-008** — Rect دوبعدی: contains/intersects/intersection/union/snap
- [x] **MATH-009** — Plane سه‌بعدی: fromPoints/fromNormalPoint/distance/raycast/boxSide
- [x] **MATH-010** — Frustum استخراج از VP + تست کره/جعبه
- [x] **MATH-011** — MathX عمومی: clamp/lerp/smoothstep/deg-rad/remap
- [x] **MATH-012** — کتابخانه Ease با ۲۵+ منحنی + جست‌وجوی نامی for JSON
- [x] **MATH-013** — damp نمایی مستقل از فریم‌ریت و moveToward
- [x] **MATH-014** — Spline خطی/Catmull-Rom/Bezier با sample/tangent/length/nearestT
- [x] **MATH-015** — Bezier درجه۲ و درجه۳ مستقل به‌صورت استاتیک
- [x] **MATH-016** — Noise پرلین 2D/3D با seed قطعی
- [x] **MATH-017** — Value noise و fBm چنداُکتاوی و ridged برای تررین
- [x] **MATH-018** — RandomStream PCG32 قطعی: range/gaussian/weighted/shuffle/fork
- [x] **MATH-019** — HalfFloat تبدیل float↔half با گردکردن به نزدیک‌ترین
- [x] **MATH-020** — Raycast تحلیلی box/sphere/capsule (slab method)
- [x] **MATH-021** — Interpolation زاویه‌ای پیوسته (shortest-arc lerp)
- [x] **MATH-022** — OBB جعبه جهت‌دار: ساخت از ترنسفورم، تست برخورد، closest point
- [x] **MATH-023** — مختصات Barycentric و تست نقطه-درون-مثلث
- [x] **MATH-024** — مختصات کروی/استوانه‌ای و تبدیل‌های متقابل
- [x] **MATH-025** — توزیع Poisson-disk برای پراکندگی نمونه‌ها
- [x] **MATH-026** — کره محصورکننده کمینه (Welzl) برای مش‌ها
- [x] **MATH-027** — پک vertex فشرده (half3 + octahedral normal) برای فرمت مش
- [x] **MATH-028** — ماتریس‌های پروجکشن اضافه: Ortho-off-center, Reversed-Z, Log depth
- [x] **MATH-029** — منحنی keyframe عمومی (float track با easing per segment)
- [x] **MATH-030** — Quaternion spline (squad) برای دوربین سینمایی

## 4. OBJ — شیء و کامپوننت (16/30)

_Entity/Component، سلسله‌مراتب، GUID و Prefab_

- [x] **OBJ-001** — مدل Actor/Entity با شناسه یکتا و نام
- [x] **OBJ-002** — سلسله‌مراتب ترنسفورم والد/فرزند با dirty propagation
- [x] **OBJ-003** — کesh ماتریس جهانی کش‌شده + normalMatrix خودکار
- [x] **OBJ-004** — بگ کامپوننت با دسترسی تایپی و رشته‌ای
- [x] **OBJ-005** — سیستم tag برای پرس‌وجوی گروهی
- [x] **OBJ-006** — پرچم active و رعایت آن در update
- [x] **OBJ-007** — اسپاون/نابودی مؤخر (flushDestroy) بدون کرش وسط update
- [x] **OBJ-008** — پرس‌وجو: find byName / byTag / byComponent
- [x] **OBJ-009** — رجیستری ComponentFactory برای ساخت از JSON
- [x] **OBJ-010** — چرخه عمر کامپوننت: onAttach/onUpdate/onTransformChanged/onDetach
- [x] **OBJ-011** — lookAt با محدودسازی pitch و ساخت از yaw
- [x] **OBJ-012** — worldAABB تبدیل جعبه محلی به جهانی با کش گوشه‌ها
- [x] **OBJ-013** — Guid پایدار hv-xxxxxxxxxxxx برای موجودیت/دارایی/سیو
- [x] **OBJ-014** — Prefab قالب JSON با instantiate و override موقعیت
- [x] **OBJ-015** — de-dup نام خودکار هنگام اسپاون چندباره prefab
- [x] **OBJ-016** — round-trip کامل Entity→Prefab→Entity با کامپوننت‌ها
- [x] **OBJ-017** — لایه‌های فیزیکی/رندر (bitmask) روی Entity
- [x] **OBJ-018** — وراثت prefab (override چندلایه parent chain)
- [x] **OBJ-019** — کامپوننت‌های attach شده به استخوان (socket) در سطح Entity API
- [x] **OBJ-020** — سیستم child-component برای رگبار‌های سلسله‌مراتبی
- [x] **OBJ-021** — پشتیبانی نیم‌موجود (pending spawn) با تاخیر یک‌فریمی
- [x] **OBJ-022** — Query cache معکوس (component → entities) برای ECS سبک
- [x] **OBJ-023** — snapshot/restore کامل وضعیت Entity برای undo ادیتور
- [x] **OBJ-024** — قفل‌گذاری خواندن برای دسترسی چندنخی امن به صحنه
- [x] **OBJ-025** — تبدیل خودکار بین prefab و scene fragment
- [x] **OBJ-026** — پشتیبانی متادیتای سفارشی (key/value) روی Entity
- [x] **OBJ-027** — ادغام/تفکیک سلسله‌مراتب با حفظ ترنسفورم جهانی
- [x] **OBJ-028** — حذف امن زیردرخت با رویداد onChildRemoved
- [x] **OBJ-029** — سنجش عمق سلسله‌مراتب + پیمایش ترتیبی
- [x] **OBJ-030** — API انعکاس برای فهرست props قابل ویرایش هر کامپوننت

## 5. WLD — دنیا و صحنه (9/25)

_بارگذاری صحنه، streaming، محیط و ابزارهای دنیاسازی_

- [x] **WLD-001** — World کانتینر سطح با پیمایش امن
- [x] **WLD-002** — بارگذاری/ذخیره صحنه JSON (محیط + درخت موجودیت)
- [x] **WLD-003** — environment صحنه: آسمان/مهر/فوگ/ambient از JSON
- [x] **WLD-004** — blackboard جهانی برای متغیرهای سناریو/اسکریپت
- [x] **WLD-005** — hook های ثبت خودکار کامپوننت ماژول‌ها (registryHooks)
- [x] **WLD-006** — StreamingManager جابجایی سلول‌های دنیا با فاصله
- [x] **WLD-007** — StreamingVolume های تعریف‌شده در صحنه
- [x] **WLD-008** — NavGrid شبکه‌ای برای مسیریابی grid-based
- [x] **WLD-009** — تولید تررین heightmap از CLI (gen terrain)
- [ ] **WLD-010** — چرخه روز/شب سراسری متصل به آسمان و نور
- [ ] **WLD-011** — سیستم آبقلمرو (فلو رودخانه/جزر و مد)
- [ ] **WLD-012** — پورتال‌های بین‌اتاقی با occlusion دقیق
- [ ] **WLD-013** — LOD چندسطحی خودکار مش بر اساس فاصله
- [ ] **WLD-014** — باد سراسری (بردار + گست/پالس) اتصال به شیدر foliage
- [ ] **WLD-015** — rule-based scattering جهانی (بایوم/شیب/ارتفاع)
- [ ] **WLD-016** — سیستم پرسپکتیو داخلی (interior/exterior blend)
- [ ] **WLD-017** — منطقه صوتی (reverb zone + music zone) تعریف در صحنه
- [ ] **WLD-018** — مرز دنیا (world bounds) با clamp فیزیکی
- [ ] **WLD-019** — checkpoint رخداد صحنه (onEnter/onLeave با EventBus)
- [ ] **WLD-020** — برچسب ناحیه (zone tag) برای منطق ترس/امن
- [ ] **WLD-021** — پیش‌گرمایش سلول‌های مجاور در پس‌زمینه (JobQueue)
- [ ] **WLD-022** — streaming بر اساس بودجه حافظه نه فقط فاصله
- [ ] **WLD-023** — ادغام صحنه‌های چندگانه در یک World اجرایی
- [ ] **WLD-024** — گرفتن اسنپ‌شات وضعیت World برای تست regression
- [ ] **WLD-025** — abstraction دنیای headless برای تست سرور

## 6. RND — رندر (27/35)

_Forward PBR، آسمان، IBL، دیکال، instancing و کیفیت‌بندی_

- [x] **RND-001** — رندرر Forward PBR با GLES 3.0 (Cook-Torrance GGX)
- [x] **RND-002** — آسمان رویه‌ای: گرادیان + ستاره hash + ماه + هاله
- [x] **RND-003** — مه نمایی Exp2 با falloff ارتفاعی
- [x] **RND-004** — سایه Directional با PCF 3×3
- [x] **RND-005** — Bloom با brightpass و گاوسی دوپاسه
- [x] **RND-006** — Tone mapping ACES + exposure
- [x] **RND-007** — Color Grade سه‌بازه (lift/gamma/gain)
- [x] **RND-008** — Vignette و Film Grain و Chromatic Aberration
- [x] **RND-009** — FXAA ضدaliasing تمام‌صفحه
- [x] **RND-010** — IBL کامل: کیوبمپ irradiance + prefiltered specular + DFG LUT
- [x] **RND-011** — بیک CPU محیط از آسمان رویه‌ای برای IBL
- [x] **RND-012** — دیکال screen-space projected با reconstruct عمق
- [x] **RND-013** — کیفیت‌بندی خودکار LOW/MEDIUM/HIGH/ULTRA
- [x] **RND-014** — Frustum culling برای مش/نور/دیکال
- [x] **RND-015** — Instancing با drawElementsInstanced
- [x] **RND-016** — سیستم Foliage: پراکندگی قطعی + باد در شیدر
- [x] **RND-017** — آب با موج Gerstner-lite و Fresnel
- [x] **RND-018** — اسکینینگ GPU تا ۶۴ مفصل با ۴ وزن
- [x] **RND-019** — تررین heightmap → مش با محاسبه نرمال
- [x] **RND-020** — خط لوله Sprite دوبعدی با atlas و batch
- [x] **RND-021** — پشتیبانی چند دوربین (main + overlay)
- [x] **RND-022** — render target مدیریت‌شده با resize خودکار
- [x] **RND-023** — pipeline state cache برای کاهش toggleهای GLES
- [x] **RND-024** — vertex format انعطاف‌پذیر (pos/normal/uv/skin/color)
- [x] **RND-025** — texture atlas runtime با padding ضد bleed
- [x] **RND-026** — تزریق decal روی سطح با بلندینگ نرمال
- [x] **RND-027** — SSAO (ambient occlusion صفحه‌ای نیم‌مقیاس)
- [ ] **RND-028** — SSR بازتاب صفحه‌ای برای آب/کف صیقلی
- [ ] **RND-029** — Motion blur سینمایی per-object
- [ ] **RND-030** — TAA جمع زمانی با jitter
- [x] **RND-031** — Volumetric fog با light shafting
- [ ] **RND-032** — سایه نقطه‌ای (point light shadows, cube map)
- [x] **RND-033** — LOD انتخاب خودکار مش (d-screen metric)
- [ ] **RND-034** — occlusion culling با عمق قبلی (Hi-Z)
- [ ] **RND-035** — multi-view استریو (VR-ready فریم‌بافر)

## 7. LGT — نورپردازی (12/20)

_انواع نور، سایه، فلیکر، فلاش‌لایت و بودجه نور موبایل_

- [x] **LGT-001** — نور Directional با رنگ/شدت/زاویه
- [x] **LGT-002** — نور Point با range و attenuation
- [x] **LGT-003** — نور Spot با cone داخلی/خارجی
- [x] **LGT-004** — سایه از نور Directional (shadow map)
- [x] **LGT-005** — ambient سراسری از environment
- [x] **LGT-006** — مهر آسمان روی نور محیط (sky contribution)
- [x] **LGT-007** — فلیکر نور (شکل موج + شدت + سرعت) — چراغ لرزان ترسناک
- [x] **LGT-008** — فلاش‌لایت مخروطی متصل به دوربین/استخوان
- [x] **LGT-009** — culling نور با frustum و فاصله
- [x] **LGT-010** — blend نرم بین دو نور هنگام جابجایی منبع
- [x] **LGT-011** — پرچم castShadow per-light
- [x] **LGT-012** — LightComponent سریالایز کامل به/از JSON
- [ ] **LGT-013** — cookie/گابور برای نور spot (سایه پنجره/پنکه)
- [x] **LGT-014** — IES profile شدت واقع‌گرایانه
- [ ] **LGT-015** — سایه نرم با VSM/ESM
- [x] **LGT-016** — light probe های تعاملی (اتاق‌های داخلی)
- [x] **LGT-017** — bounce light شبیه‌سازی‌شده یک‌مرحله‌ای
- [x] **LGT-018** — animation شدت نور از Sequencer
- [ ] **LGT-019** — کنترل CVar ای کیفیت سایه per-light
- [x] **LGT-020** — lightning flash رخدادی (روشنایی لحظه‌ای جهانی)

## 8. MAT — متریال (12/20)

_PBR، blend modes، تکسچر و قابلیت‌های سطح_

- [x] **MAT-001** — MaterialDef PBR: albedo/metallic/roughness/normal/emissive
- [x] **MAT-002** — بارگذاری بافت از pipeline (.hvt) با mip خودکار
- [x] **MAT-003** — حالت‌های blend: opaque / alpha-test / alpha-blend
- [x] **MAT-004** — emissive مستقل + اتصال به bloom
- [x] **MAT-005** — tiling/offset per-material
- [x] **MAT-006** — vertex color به‌عنوان ضریب رنگ
- [x] **MAT-007** — مرتب‌سازی شفاف‌ها back-to-front خودکار
- [x] **MAT-008** — مواد دوبعدی: sprite با unlit + tint
- [x] **MAT-009** — double-sided و culling mode per-material
- [x] **MAT-010** — render queue دستی (override ترتیب)
- [x] **MAT-011** — پشتیبانی مواد قدیمی (diffuse-only) برای asset ساده
- [x] **MAT-012** — سریالایز material در مدل .hvm
- [ ] **MAT-013** — triplanar mapping برای تررین بدون UV
- [ ] **MAT-014** — parallax occlusion mapping
- [ ] **MAT-015** — subsurface scattering تقریبی (پوست/موم)
- [ ] **MAT-016** — clearcoat/sheen (پارچه و لاک)
- [ ] **MAT-017** — matcap برای پیش‌نمایش سریع
- [ ] **MAT-018** — displacement با tessellation سبک
- [x] **MAT-019** — material instance با override پارامتر (بدون shader جدید)
- [x] **MAT-020** — ادیتور متریال node-based متنی (JSON graph)

## 9. PST — پست‌پروسس (8/20)

_Bloom، ACES، grain، جنون و افکت‌های سینمایی_

- [x] **PST-001** — Bloom تنظیم‌پذیر (threshold/强度/radius)
- [x] **PST-002** — ACES tone mapping با exposure override
- [x] **PST-003** — Color grading با saturation/contrast/temperature
- [x] **PST-004** — Vignette با شعاع/نرمی
- [x] **PST-005** — Film grain با اندازه/سرعت/شدت
- [x] **PST-006** — Chromatic aberration شعاعی
- [x] **PST-007** — FXAA با تنظیم کیفیت
- [x] **PST-008** — Exposure خودکار بر اساس میان‌بینایی صحنه
- [x] **PST-009** — افکت جنون (sanity filter): موج/رنگ/ریزپرش وابسته به وضعیت بازی
- [x] **PST-010** — radial blur برای ضربه/شتاب
- [x] **PST-011** — depth of field سینمایی (نیم‌مقیاس)
- [x] **PST-012** — god rays از نور Directional
- [x] **PST-013** — retro CRT / halftone برای سکانس‌های ویدئویی
- [x] **PST-014** — outline دور شخصیت‌های قابل تعامل
- [x] **PST-015** — screen-space blood splatter overlay
- [x] **PST-016** — distortion (موج حرارتی/آب) با normal map
- [x] **PST-017** — posterize/pixelate برای فلش‌بک
- [x] **PST-018** — letterbox سینمایی با fade
- [x] **PST-019** — crossfade بین دو LUT رنگی (روز/جنون)
- [x] **PST-020** — motion blur دوربین هنگام head-turn سریع

## 10. PHY — فیزیک (35/35)

_اجسام صلب، جوینت، کاراکتر کنترلر و تررین_

- [x] **PHY-001** — کولایدر Box/Sphere/Capsule
- [x] **PHY-002** — اجسام استاتیک و dynamic rigid body
- [x] **PHY-003** — حل‌گر ضربه dynamic-dynamic با اصطکاک کولن
- [x] **PHY-004** — restitution برخورد سرعتی
- [x] **PHY-005** — خواب اجسام (sleep) با بیداری رخدادی
- [x] **PHY-006** — gravity سراسری per-world
- [x] **PHY-007** — drag خطی/زاویه‌ای
- [x] **PHY-008** — Joint فاصله/فنر/hinge
- [x] **PHY-009** — انفجار شعاعی (impulse radial)
- [x] **PHY-010** — رویداد برخورد از EventBus (begin/end)
- [x] **PHY-011** — Raycast دقیق box/sphere/capsule با نرمال و فاصله
- [x] **PHY-012** — Character Controller با collide-and-slide
- [x] **PHY-013** — step-up برای پله تا ارتفاع مجاز
- [x] **PHY-014** — ground snap برای شیب‌های ملایم
- [x] **PHY-015** — تریگر enter/exit رخدادی
- [x] **PHY-016** — force/impulse API روی RigidBody
- [x] **PHY-017** — sweep test کوتاه برای حرکت ایمن گلوله
- [x] **PHY-018** — lock axes (مثلاً چرخش فقط حول Y)
- [x] **PHY-019** — Ragdoll کامل با مفاصل محدوددار
- [x] **PHY-020** — Cloth (شبه‌شباله Verlet) با برخورد کره
- [x] **PHY-021** — خودرو ساده (4 چرخ + تعادل)
- [x] **PHY-022** — buoyancy در حجم آب
- [x] **PHY-023** — wind zone فیزیکی (فشار روی rigid body)
- [x] **PHY-024** — CCD برای اجسام تند (glancing)
- [x] **PHY-025** — hinge با موتور (door physics)
- [x] **PHY-026** — constraint عمومی نقطه‌ای
- [x] **PHY-027** — تقسیم فضایی broadphase (uniform grid) با refresh خودکار
- [x] **PHY-028** — layer mask برخورد per-body
- [x] **PHY-029** — material فیزیکی (اصطکاک/بازگشت per-pair)
- [x] **PHY-030** — شکستن اجسام (destruction pre-fractured)
- [x] **PHY-031** — bajar متصل به انیمیشن (foot planting)
- [x] **PHY-032** — physics substep برای کیفیت تماس
- [x] **PHY-033** — تله‌فیزیک (trap spring/snap)
- [x] **PHY-034** — آب‌خوردن اجسام شناور با خط‌کشی ساده
- [x] **PHY-035** — استرس‌سنج joint (پاره شدن طناب)

## 11. COL — برخورد (25/25)

_شکل‌ها، raycast، نفوذ و broadphase_

- [x] **COL-001** — AABB overlap با valid check
- [x] **COL-002** — Sphere-Sphere و Sphere-AABB
- [x] **COL-003** — Capsule-Capsule (segment distance)
- [x] **COL-004** — Ray vs Box/Sphere/Capsule تحلیلی
- [x] **COL-005** — نقطه نزدیک‌ترین روی segment (closest point)
- [x] **COL-006** — نفوذ (penetration depth) + نرمال جداکننده
- [x] **COL-007** — رویداد برخورد با جفت entity
- [x] **COL-008** — فیلتر برخورد بر اساس tag/prچرچ
- [x] **COL-009** — trigger volumes با مانیتور ورود/خروج
- [x] **COL-010** — تست نقطه درون AABB/Capsule
- [x] **COL-011** — OBB vs OBB با SAT
- [x] **COL-012** — OBB vs Capsule
- [x] **COL-013** — Convex hull ساده (بسته تا ۳۲ راس)
- [x] **COL-014** — Mesh vs Capsule (برخورد دقیق با مثلث‌ها)
- [x] **COL-015** — heightmap collision مستقیم برای تررین
- [x] **COL-016** — sweep capsule برای حرکت کاراکتر (بین‌راهی)
- [x] **COL-017** — broadphase AABB tree (BVH) پویا
- [x] **COL-018** — contact manifolds چندنقطه‌ای
- [x] **COL-019** — collision group matrix (32×32) قابل ذخیره در پروژه
- [x] **COL-020** — گزارش آماری برخورد (تعداد/هزینه) برای profiler
- [x] **COL-021** — raycast batch (مثلاً ۳۲ پرتو یک‌جا)
- [x] **COL-022** — raycast از دوربین برای pick ویرایشگری
- [x] **COL-023** — پیش‌بینی مسیر پرتابه (trajectory preview)
- [x] **COL-024** — برخورد صدا-سطح (متریال برای انتخاب صدا)
- [x] **COL-025** — شبیه‌سازی آب سطحی برای شناورها

## 12. AUD — صدا (30/30)

_میکسر، DSP، فضاسازی و رندر آفلاین_

- [x] **AUD-001** — AudioEngine مدیریت device + بلندینگ خروجی اندروید
- [x] **AUD-002** — Mixer چندکاناله با بلندینگ استریو
- [x] **AUD-003** — AudioSource با clip/loop/volume/pitch
- [x] **AUD-004** — 3D spatialization (فاصله/پن/attenuation)
- [x] **AUD-005** — دیکدر OGG خالص جاوا (Android + Desktop)
- [x] **AUD-006** — دیکدر WAV PCM16/Float
- [x] **AUD-007** — EQ سه‌بازه (low/mid/high shelf)
- [x] **AUD-008** — Compressor با threshold/ratio/attack/release
- [x] **AUD-009** — Reverb freeverb (room size/damp/wet)
- [x] **AUD-010** — Lowpass/Highpass biquad
- [x] **AUD-011** — ducking موسیقی هنگام دیالوگ/رویداد
- [x] **AUD-012** — variation تصادفی pitch/volume ضد خستگی شنیداری
- [x] **AUD-013** — crossfade بین دو تراک موسیقی
- [x] **AUD-014** — pause/resume سراسری با حفظ موقعیت
- [x] **AUD-015** — audio-render آفلاین (mixdown به WAV) برای تست
- [x] **AUD-016** — AudioFX زنجیره‌ای per-source و master
- [x] **AUD-017** — doppler برای منابع متحرک
- [x] **AUD-018** — HRTF binaural برای هدفون
- [x] **AUD-019** — convolution reverb با impulse record
- [x] **AUD-020** — سیستم bus (sfx/music/voice) با ولوم مستقل CVar
- [x] **AUD-021** — استریم فایل بزرگ از دیسک (نمونه‌های طولانی)
- [x] **AUD-022** — synth procedural (درام/باد/وزوز) بدون فایل
- [x] **AUD-023** — occlusion صدا (دیوار → lowpass + کاهش)
- [x] **AUD-024** — reverb zone های صحنه‌ای
- [x] **AUD-025** — listener interpolation نرم (ضد jitter)
- [x] **AUD-026** — media session integration اندروید (focus)
- [x] **AUD-027** — cache decode با LRU حافظه محدود
- [x] **AUD-028** — loop seamless با crossfade لبه
- [x] **AUD-029** — audio graph ورودی سفارشی (_send bus)
- [x] **AUD-030** — subtitle-sync رخداد (زمان‌بندی متن با صدا)

## 13. ANI — انیمیشن (25/25)

_glTF، بلند اسپیس، IK و state machine_

- [x] **ANI-001** — لود glTF با انیمیشن (LINEAR/STEP/CUBICSPLINE bake)
- [x] **ANI-002** — AnimClip با sampling زمانی بهینه
- [x] **ANI-003** — Animator با لایه play/queue/stop
- [x] **ANI-004** — Crossfade دوتایی با slerp مفصلی
- [x] **ANI-005** — State Machine JSON با شرط var و رویداد
- [x] **ANI-006** — Blend Space 1D/2D با inverse-distance تا ۴ کلیپ
- [x] **ANI-007** — Animation notifies (رخداد در زمان مشخص کلیپ)
- [x] **ANI-008** — socket/Attachment برای اتصال به مفصل
- [x] **ANI-009** — IK تحلیلی دواستخوان
- [x] **ANI-010** — FootIK برای ایستادن روی سطح شیبدار
- [x] **ANI-011** — look-at سر (نظر کردن به هدف)
- [x] **ANI-012** — اسکلت bind pose + hierarchy محاسبه جهانی
- [x] **ANI-013** — retarget بین اسکلت‌های متفاوت
- [x] **ANI-014** — لایه additive (مثلاً نفس بالای walk)
- [x] **ANI-015** — root motion برداشت جابجایی از کلیپ
- [x] **ANI-016** — compress کلیپ (quantize rotation) با خطای مجاز
- [x] **ANI-017** — procedural lean (تمایل به جهت حرکت)
- [x] **ANI-018** — lip sync ساده (viseme از دامنه صدا)
- [x] **ANI-019** — facial rig سبک (۳۲ شکل‌دهنده)
- [x] **ANI-020** — blend توپولوژی همزمان ۳+ کلیپ
- [x] **ANI-021** — IK چندزنجیره‌ای (دست + سر همزمان)
- [x] **ANI-022** — animation drift اصلاح (بازگشت به مسیر منطقی)
- [x] **ANI-023** — event payload سفارشی در notify
- [x] **ANI-024** — تجمیع انیمیشن فیزیکی (springs on bones)
- [x] **ANI-025** — پیش‌نمایش CLI انیمیشن (dump pose در tick مشخص)

## 14. AI — هوش مصنوعی (10/30)

_درخت رفتار، ادراک، مسیریابی، EQS و کارگردان ترس_

- [x] **AI-001** — Behavior Tree کامل: selector/sequence/parallel/inverter/repeat
- [x] **AI-002** — گره‌های wait/condition/action/set_bb/fire_event
- [x] **AI-003** — Blackboard (VarTable) با مقدار دهی از اسکریپت
- [x] **AI-004** — Perception بینایی: مخروط دید + occlusion raycast
- [x] **AI-005** — Perception شنوایی: رویداد noise با شعاع
- [x] **AI-006** — NavGrid مسیریابی A* با هزینه شیب
- [x] **AI-007** — CrowdAvoidance دوری از برخورد عابرها
- [x] **AI-008** — EQS پرس‌وجوی محیطی (بهترین نقطه/پوشش/فاصله)
- [x] **AI-009** — Agent یکپارچه: هدف/حالت/درک محیط
- [x] **AI-010** — پاترول waypoint ساده با حلقه/برگشت
- [ ] **AI-011** — کارگردان ترس (Horror Director): تنش/آرامش سنجش‌شده
- [ ] **AI-012** — مدیریت spawn-budget دشمن بر اساس اضطراب بازیکن
- [ ] **AI-013** — حافظه دشمن از بازیکن (آخرین موقعیت دیده‌شده)
- [ ] **AI-014** — meter شک (suspicion) با decay
- [ ] **AI-015** — استرینگ رفتاری: جست‌وجو پس از گم‌کردن هدف
- [ ] **AI-016** — GOAP برنامه‌ریزی هدف‌محور
- [ ] **AI-017** — Utility AI با scoring پیوسته
- [ ] **AI-018** — Formation گروهی (V/خط/محاصره)
- [ ] **AI-019** — steering کامل: seek/flee/wander/arrival/alignment
- [ ] **AI-020** — dialogue tree با شرط و متغیر
- [ ] **AI-021** — فاکشن و نسبت‌ها (friendly/neutral/hostile)
- [ ] **AI-022** — تولید مسیر روی تررین پیوسته (نه فقط grid)
- [ ] **AI-023** — درک محیط چندحسی (بوی بازیکن!)
- [ ] **AI-024** — پزشکی ساده NPC (زخم → فرار/تقلا)
- [ ] **AI-025** — تأخیر واکنش انسانی (reaction time random)
- [ ] **AI-026** — AI director for pacing audio (جمع با ENV)
- [ ] **AI-027** — تست خودکار AI: سناریو asserts (اینتنت اشغال می‌شود)
- [ ] **AI-028** — دیده‌بانی دید در تاریکی با flashlight (تبه‌سازی cone)
- [ ] **AI-029** — سیستم هیجان صوتی AI (صدای قدم بازیکن)
- [ ] **AI-030** — هماهنگی چنددشمن (pincer/flank)

## 15. UI — رابط کاربری (8/20)

_ویجت‌ها، طرح، فونت و RTL_

- [x] **UI-001** — UiDrawList فوری (immediate) با batch دوبعدی
- [x] **UI-002** — UIWidgets2: دکمه/برچسب/پنل/اسلایدر
- [x] **UI-003** — UIManager مدیریت صفحه‌ها (push/pop)
- [x] **UI-004** — طرح relative (anchor + margin) برای موبایل
- [x] **UI-005** — فونت سفارشی + شکل‌دهی متن (shaping) اندروید
- [x] **UI-006** — فوکوس کیبورد/دسته بین ویجت‌ها
- [x] **UI-007** — UI scale دپی-آگاه
- [x] **UI-008** — toast/پیام موقت روی صفحه
- [ ] **UI-009** — RTL کامل فارسی در چیدمان UI
- [ ] **UI-010** — rich text (رنگ/بولد درون رشته)
- [ ] **UI-011** — scroll view با inertia لمسی
- [ ] **UI-012** — list مجازی‌شده (هزار آیتم)
- [ ] **UI-013** — انیمیشن UI (Ease از موتور) روی transform
- [ ] **UI-014** — safe-area گوشی (notch) خودکار
- [ ] **UI-015** — gamepad navigation map
- [ ] **UI-016** — تولید UI از JSON (طرح declarative)
- [ ] **UI-017** — theme/palette سراسری قابل swap
- [ ] **UI-018** — localization-aware اندازه خودکار کادر متن
- [ ] **UI-019** — استایل‌دهی نیمه‌شفاف blur پس‌زمینه منو
- [ ] **UI-020** — دستیار HUD (خون/چراغ/هدف) سبک

## 16. INP — ورودی (6/15)

_کنش‌ها، لمس، دسته و نگاشت مجدد_

- [x] **INP-001** — InputActions نگاشت کنش → کلید/لمس
- [x] **INP-002** — keymap JSON قابل ویرایش پروژه
- [x] **INP-003** — تچ چندانگشتی + gesture ساده (swipe)
- [x] **INP-004** — ماوس/کیبورد دسکتاپ برای تست
- [x] **INP-005** — deadzone آنالوگ قابل تنظیم
- [x] **INP-006** — فریم‌بافی ورود (تایم‌استمپ رخداد ورودی)
- [ ] **INP-007** — پشتیبانی گیم‌پد اندروید (دسته بلوتوثی)
- [ ] **INP-008** — لرزش (vibration) رخدادی
- [ ] **INP-009** — combo detection (ضرب‌های سریع)
- [ ] **INP-010** — context-sensitive action map (منو/بازی)
- [ ] **INP-011** — نگاشت مجدد live از منو
- [ ] **INP-012** — trigger آنالوگ برای دقت شلیک
- [ ] **INP-013** — ذخیره/بازیابی پروفایل ورودی کاربر
- [ ] **INP-014** — تست خودکار ورودی (replay events)
- [ ] **INP-015** — دسترسی هم‌زمان چند ورودی (پخش لوکال ۲ نفره سبک)

## 17. NET — شبکه (5/20)

_کانال TCP، پیام، سشن و replication_

- [x] **NET-001** — TcpChannel طول-پیشوندی (client + host accept)
- [x] **NET-002** — NetMessage سریالایز/دیسریالایز نوع‌دار
- [x] **NET-003** — Session مدیریت اتصال + پینگ
- [x] **NET-004** — تست loopback درون‌پردازشی (net test)
- [x] **NET-005** — کمپرس payload ساده (varint)
- [ ] **NET-006** — UDP با قابلیت اطمینان لایه‌ای
- [ ] **NET-007** — snapshot delta (فقط تغییرات state)
- [ ] **NET-008** — client prediction + server reconciliation
- [ ] **NET-009** — interpolation ازدحام برای موجودیت‌های راه‌دور
- [ ] **NET-010** — lobby/اتاق با کد پیوستن
- [ ] **NET-011** — replay recording از جریان ورودی
- [ ] **NET-012** — authority transfer (host migration ساده)
- [ ] **NET-013** — رمزنگاری handshake ساده (XOR+nonce؛ امنیت کامل بعداً)
- [ ] **NET-014** — rate limit و backpressure صف ارسال
- [ ] **NET-015** — observer mode تماشاچی
- [ ] **NET-016** — sync متغیر blackboard بین کلاینت‌ها
- [ ] **NET-017** — remote console (سرور بدون سر)
- [ ] **NET-018** — مانیتور شبکه CLI (pkt/s, bytes/s, loss)
- [ ] **NET-019** — شناسه نسخه پروتکل و سازگاری
- [ ] **NET-020** — تست stress با ۳۲ کلاینت شبیه‌سازی‌شده

## 18. SAV — ذخیره (4/15)

_اسلات‌ها، سریالایز، مهاجرت و امنیت سیو_

- [x] **SAV-001** — SaveSystem JSON با اسلات‌های نام‌دار
- [x] **SAV-002** — سریالایز وضعیت World (entities/components)
- [x] **SAV-003** — سریالایز متغیرهای blackboard/سناریو
- [x] **SAV-004** — شمارش/فهرست/حذف اسلات‌ها از CLI
- [ ] **SAV-005** — فرمت باینری فشرده (کوچک‌تر از JSON)
- [ ] **SAV-006** — checksum + صحت‌سنجی فایل سیو
- [ ] **SAV-007** — مهاجرت نسخه سیو (migration hooks)
- [ ] **SAV-008** — autosave سیاست‌دار (هر N دقیقه/رخداد)
- [ ] **SAV-009** — snapshot incrementally (فقط دلتا)
- [ ] **SAV-010** — ذخیره در دایرکتوری کاربر اندروید امن
- [ ] **SAV-011** — cloud sync hook (تبدیل پوشه سیو → سرویس)
- [ ] **SAV-012** — رمزگذاری ساده سیو (ضد ویرایش سریع)
- [ ] **SAV-013** — screen capture ذخیره‌شده با سیو (کاور اسلات)
- [ ] **SAV-014** — backup چرخشی (۳ نسخه آخر)
- [ ] **SAV-015** — ادغام سیو چندبخشی (chapter select)

## 19. LOC — بومی‌سازی (4/10)

_رشته‌ها، آرگومان، RTL و فونت fallback_

- [x] **LOC-001** — Localization با فایل strings/ کلیدی
- [x] **LOC-002** — فرمت آرگومان‌دار (سلام {name})
- [x] **LOC-003** — fallback به زبان پیش‌فرض برای کلید گم
- [x] **LOC-004** — تزریق متن از CLI و اسکریپت
- [ ] **LOC-005** — RTL کامل: کات/شماره/میکس جهت
- [ ] **LOC-006** — پشتیبانی جمع (plural rules) فارسی/عربی/انگلیسی
- [ ] **LOC-007** — pseudo-localization برای تست چیدمان
- [ ] **LOC-008** — hot-reload فایل ترجمه هنگام اجرا
- [ ] **LOC-009** — font fallback خودکار برای گلیف‌های گم
- [ ] **LOC-010** — قالب تاریخ/عدد locale-آگاه

## 20. AST — دارایی (9/12)

_پایپ‌لاین import، اطلس، glTF و اعتبارسنجی_

- [x] **AST-001** — AssetDB رجیستری دارایی‌های لودشده
- [x] **AST-002** — پایپ‌لاین import خام → فرمت موتور (.hvt/.hvm)
- [x] **AST-003** — AtlasPacker بسته‌بندی اسپرایت‌ها با padding
- [x] **AST-004** — لودر glTF با مش/ماده/انیمیشن
- [x] **AST-005** — فرمت مدل .hvm بومی با AABB
- [x] **AST-006** — فرمت بافت .hvt بومی (mip/فرمت پیکسل)
- [x] **AST-007** — manifest.json دارایی‌ها با هش
- [x] **AST-008** — validate کل پروژه (JSON/ارجاع/کامپوننت/اسکریپت)
- [x] **AST-009** — atlas CLI command با گزارش پرهیز از bleed
- [ ] **AST-010** — گراف وابستگی دارایی (چه چیزی به چه چیزی)
- [ ] **AST-011** — hot reload دارایی هنگام اجرا
- [ ] **AST-012** — اولویت‌بندی streaming دارایی

## 21. SEQ — سکانس‌ساز (4/8)

_خط زمانی، کی‌فریم و تراک دوربین/رویداد_

- [x] **SEQ-001** — Sequencer خط زمانی با track های متعدد
- [x] **SEQ-002** — keyframe float/vec3 با easing per-key
- [x] **SEQ-003** — track دوربین (cut + smooth)
- [x] **SEQ-004** — track رویداد (fire event در زمان)
- [ ] **SEQ-005** — track انیمیشن (play clip at time)
- [ ] **SEQ-006** — track نور/مهر/فوگ (environment animate)
- [ ] **SEQ-007** — پیش‌نمایش headless (dump وضعیت در t)
- [ ] **SEQ-008** — ادغام spline دوربین با Spline موتور

## 22. VFX — افکت‌های بصری (8/25)

_ذرات، دیکال، خون، آب‌وهوا و شوک‌های سینمایی_

- [x] **VFX-001** — ParticleEmitter با اسیلاتور spawn
- [x] **VFX-002** — ParticleLibrary فرمت‌های آماده (دود/جرقه/خاک)
- [x] **VFX-003** — gravia/gravity/drag/ذرات
- [x] **VFX-004** — بلندینگ additive/alpha for particles
- [x] **VFX-005** — particle texture sheet animation
- [x] **VFX-006** — burst spawn رخدادی
- [x] **VFX-007** — decal سقوط (خون روی دیوار)
- [x] **VFX-008** — trail متصل به متحرک
- [ ] **VFX-009** — خون واقع‌گرایانه (جهت سطح + حجم)
- [ ] **VFX-010** — gore کنترل‌شده (قطعات)
- [ ] **VFX-011** — sparks با برخورد سطح
- [ ] **VFX-012** — باران/برف جهانی با باد
- [ ] **VFX-013** — رعد و برق رخدادی (نور + صدا)
- [ ] **VFX-014** — دود پویا با occlusion ساده
- [ ] **VFX-015** — dissolve material (محوشدن اجسام)
- [ ] **VFX-016** — muzzle flash سه‌مرحله‌ای
- [ ] **VFX-017** — screen shake با trauma decay
- [ ] **VFX-018** — hit stop (فریم یخ لحظه‌ای)
- [ ] **VFX-019** — sword swing arc mesh
- [ ] **VFX-020** — aura/حلقه جادویی روی زمین
- [ ] **VFX-021** — ash/ذرات محیطی ترسناک (اتاق خالی)
- [ ] **VFX-022** — پرواز پرنده/شب‌تاب (ambient life)
- [ ] **VFX-023** — سیستم کوت سینمایی (خون روی لنز)
- [ ] **VFX-024** — pooling کامل ذرات (بدون GC)
- [ ] **VFX-025** — پیش‌ساخت وینیت خون per-camera

## 23. ENV — محیط (5/20)

_آسمان، آب‌وهوا، چرخه شبانه‌روز و جزئیات ترسناک_

- [x] **ENV-001** — آسمان رویه‌ای قابل تنظیم (زمان/آلودگی)
- [x] **ENV-002** — فوگ با رنگ/چگالی/ارتفاع
- [x] **ENV-003** — ambient hemisphere (آسمان/زمین)
- [x] **ENV-004** — باد شیدری foliage (دو فرکانس)
- [x] **ENV-005** — پرچم ستاره/ماه/ابر رویه‌ای
- [ ] **ENV-006** — چرخه روز/شب با زمان سیو
- [ ] **ENV-007** — سیستم آب‌وهوا (صاف/باران/مه غلیظ) با ترنزیشن
- [ ] **ENV-008** — فصل‌ها (رنگ برگ/دما/صدا)
- [ ] **ENV-009** — خیس‌شدگی سطوح بعد باران (roughness override)
- [ ] **ENV-010** — انباشت برف (لایه سفید روی نرمال بالایی)
- [ ] **ENV-011** — پرنده/خفاش پرنده ambient با صدا
- [ ] **ENV-012** — creaks/debris رخداد محیطی تصادفی (خانه ترسناک)
- [ ] **ENV-013** — اتاق‌های دمادست (temperature فیک برای steam)
- [ ] **ENV-014** — صدای باد وابسته به سرعت باد
- [ ] **ENV-015** — تاریکی واقعی (نور صفر → بلک، نه خاکستری)
- [ ] **ENV-016** — حس امن/خطر فضایی (music zone رخدادی)
- [ ] **ENV-017** — پروژکتور ویدئویی درون‌بازی (رندر به texture)
- [ ] **ENV-018** — آینه planar ساده
- [ ] **ENV-019** — پنجره‌های باران‌خورده (refraction سبک)
- [ ] **ENV-020** — سطح آب با انعکاس آسمان

## 24. DBG — دیباگ (7/20)

_draw دیباگ، پروفایلر، selftest و ابزارهای عیب‌یابی_

- [x] **DBG-001** — DebugDraw خطی (box/sphere/line/text) برای فیزیک و AI
- [x] **DBG-002** — Profiler با CSV export و summary
- [x] **DBG-003** — selftest خودکار با ۱۷+ چک موتور
- [x] **DBG-004** — Log capture برای تست CLI
- [x] **DBG-005** — CrashHandler گزارش فایل‌محور
- [x] **DBG-006** — bench throughput فیزیک/AI
- [x] **DBG-007** — validate پروژه با پیام خطای قابل فهم
- [ ] **DBG-008** — پنل کنسول درون‌بازی (خروجی لاگ روی صفحه)
- [ ] **DBG-009** — گراف FPS/فریم‌تایم زنده روی گوشی
- [ ] **DBG-010** — inspector Entity درون‌بازی (انتخاب/ویرایش)
- [ ] **DBG-011** — حالت توقف + قدم‌زدن فریم از کنسول
- [ ] **DBG-012** — replay گرداب ورودی (بازپخش آخرین ۶۰ ثانیه)
- [ ] **DBG-013** — heat map هزینه رندر (overlay رنگی)
- [ ] **DBG-014** — آمار per-system در یک جدول واحد
- [ ] **DBG-015** — assert های پروژه‌ای (تعریف شرط در JSON)
- [ ] **DBG-016** — unit test hook برای اسکریپت‌های بازی
- [ ] **DBG-017** — مانیتور نشتی حافظه بلندمدت (گزارش هفتگی)
- [ ] **DBG-018** — فهرست رویدادهای EventBus زنده (فیلتر)
- [ ] **DBG-019** — تولید خودکار bug report (zip لاگ+سیو)
- [ ] **DBG-020** — telemetry محلی (بدون شبکه) برای تحلیل سشن

## 25. SCR — اسکریپت (6/15)

_FlowRunner، عبارت‌سنج، دیباگر و ایمنی_

- [x] **SCR-001** — FlowRunner اجرای اسکریپت رخدادی
- [x] **SCR-002** — ScriptComponent اتصال اسکریپت به Entity
- [x] **SCR-003** — Expr ارزیاب عبارت (ریاضی/منطقی/متغیر)
- [x] **SCR-004** — VarTable با scope جهانی/محلی
- [x] **SCR-005** — فراخوانی CVar/EventBus از اسکریپت
- [x] **SCR-006** — اعلان رویداد سفارشی از اسکریپت
- [ ] **SCR-007** — hot reload اسکریپت بدون ری‌استارت
- [ ] **SCR-008** — breakpoint/step دیباگر CLI
- [ ] **SCR-009** — استاندارد کتابخانه زمان/ریاضی/رشته غنی‌تر
- [ ] **SCR-010** — coroutine/sleep در اسکریپت
- [ ] **SCR-011** — attach debugger به دستگاه اندروید از دسکتاپ
- [ ] **SCR-012** — کامپایل به بایت‌کد داخلی (سرعت)
- [ ] **SCR-013** — sandbox مجوزها (اسکریپت محدود به API امن)
- [ ] **SCR-014** —  profiler per-script (هزینه هر فایل)
- [ ] **SCR-015** — تولید مستندات API اسکریپت خودکار

## 26. VR — واقعیت مجازی (0/10)

_رندر دوچشمی، ردیابی سر و راحتی_

- [ ] **VR-001** — چشم‌ک چپ/راست با projection جدا (multi-view)
- [ ] **VR-002** — head tracking از sensor اندروید
- [ ] **VR-003** — reprojection async (سبک)
- [ ] **VR-004** — کنترلر 3DoF لمسی
- [ ] **VR-005** — کالیبراسیون IPD
- [ ] **VR-006** —  Comfort vignette هنگام چرخش
- [ ] **VR-007** — پروفایل کارایی VR (۹۰fps هدف)
- [ ] **VR-008** — کاهش کیفیت خودکار در VR
- [ ] **VR-009** — صدا binaural VR
- [ ] **VR-010** — تست headless VR pipeline

## 27. EXT — ابزار و اکوسیستم (4/10)

_ساخت APK، صادرات، CI و افزونه‌ها_

- [x] **EXT-001** — build apk کامل: بسته‌بندی + امضا keystore
- [x] **EXT-002** — gen مش‌های اولیه از CLI (box/plane/sphere/capsule/cylinder/terrain)
- [x] **EXT-003** — new پروژه اسکفولد کامل با پوشه‌ها
- [x] **EXT-004** — سازگاری Gradle 8.4 AGP برای بازسازی بازی
- [ ] **EXT-005** — خروجی itch.io (zip + page template)
- [ ] **EXT-006** — قالب CI گیت‌هاب (build+test خودکار)
- [ ] **EXT-007** — افزونه Blender export (.hvm مستقیم)
- [ ] **EXT-008** — Language Server پروتکل برای اسکریپت (VSCode)
- [ ] **EXT-009** — پروفایلر ریموت (نمایش روی دسکتاپ)
- [ ] **EXT-010** — بسته‌سازی CLI به single-jar قابل توزیع
