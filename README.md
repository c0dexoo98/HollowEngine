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
> چک‌لیست مستر ۵۷۰ قابلیت: `docs/FEATURES.md` (**۵۷۰/۵۷۰ کامل** ✅).
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

> **v2.3** (`hollow-simulation`, Batch 3: PHY/COL/AUD/ANI) —
> **فیزیک پیشرفته**: Ragdoll مفاصل محدوددار + قطع عضو، **Cloth Verlet** با برخورد کره و پارگی،
> **خودرو ۴چرخ** (تعلیق raycast + گریپ لغزش + تعادل anti-roll)، حجم آب با **شناوری و خط آب پایدار**،
> WindZone با وزش/تلاطم، CCD ضدتونل‌زنی، **broadphase شبکه یکنواخت**، لایه برخورد per-body،
> **متریال فیزیکی** (۱۰ پریست + قواعد ترکیب per-pair)، شکستن pre-fractured با رویداد،
> تله فنری/کوبشی، **سنجه استرس جوینت + پارگی**، substep فیزیک، Point constraint،
> هینج موتوردار با محدوده (د در)، foot planting ضدسرخوردن.
> **برخورد**: کتابخانه `CollisionLib` — OBB↔OBB با SAT پنج‌محوره و MTV، OBB↔Capsule،
> ConvexHull (زنجیره یکنوا)، mesh↔capsule مثلث‌دقیق، **Heightfield** با نرمال گرادیانی،
> sweep capsule، **BVH پویا** (درخت AABB)، Contact manifold چندنقطه، ماتریس گروه ۳۲×۳۲
> با JSON، آمار برخورد برای پروفایلر، raycast دسته‌ای، **پیک دوربین از NDC**،
> پیش‌نمایش مسیر پرتابه با کمانک، سطح آب موج‌دار برای شناورها.
> **صدا**: Doppler با سرعت نسبی، **HRTF باینورال** (ITD+ILD+نت پینی)،
> **ریورب کانولوشن FFT** (overlap-add پارتیشن‌بندی + IR سنتزی)، اتوبوس‌های ۵گانه متصل به
> CVar، **استریم WAV از دیسک**، سینت پروسیجرال (باد/وزوز/ضرب)، **مسدودسازی صدا**
> (lowpass+تضعیف)، ReverbZone سحنی با کراس‌بلند، **هموارسازی لیسنر**، پل AudioFocus
> اندروید (MediaSession)، **کش LRU دیکود**، لوپ بی‌درز با کراس‌فید لبه، ارسال سفارشی
> به Delay، **زیرنویس همگام با کلاک صدا**.
> **انیمیشن**: Retarget خودکار بین اسکلت‌ها، لایه Additive، **Root Motion** با drift fix،
> فشرده‌سازی کلیپ (snorm16 + خطاسنج)، lean پروسیجرال، **LipSync ویسومی**،
> **ریگ چهره ۳۲ blendshape**، میکسر چندکلیپ (۳+ همزمان)، MultiChain IK اولویت‌دار،
> Notify با payload سفارشی، **فنر استخوان** (حرکت ثانویه)، `hollow pose` دامپ JSON.
> تست: `hollow selftest` — ۸۰+ تست (همه سبز).
>
> **v2.4** (`hollow-hardening`, Batch 4+5+6) —
> **دنیا (WLD/ENV)**: چرخه روز/شب کامل متصل به آسمان/نور/مه با غروب طلایی و ستاره‌های شب،
> باد سراسری با گست/تلاطم، پورتال‌های بین‌اتاقی با occlusion، پخش قطعی props
> (ارتفاع/شیب/بایوم)، مرز دنیا با دیوار نرم، تریگرهای checkpoint با zone-tag،
> منطقه صوتی با کراس‌بلند، آب‌وهوای ۵حالته (باران/برف/مه/طوفان) با خیس‌شدگی و
> انباشت برف، فصل‌ها با رنگ برگ و دما، رعد/creak رخدادی، زندگی محیطی (کرم شب‌تاب/خاکستر).
> **هوش مصنوعی**: کارگردان ترس (تنش/بودجه spawn)، حافظه دشمن + متر شک + تأخیر واکنش انسانی،
> **GOAP planner (A*)**، Utility AI، Formation (V/خط/محاصره) با تخصیص حریصانه،
> steering کامل (seek/flee/arrival/wander/alignment/cohesion/separation)،
> درخت دیالوگ JSON فارسی، فاکشن‌ها، هماهنگی pincer/flank، کوری فلش‌لایت و شنوایی قدم.
> **رندر**: SSR تنظیم‌پذیر، motion blur per-object سرعت‌محور، TAA با دنباله Halton،
> سایه نقطه‌ای بودجه‌دار، Hi-Z occlusion، استریو VR (IPD/سر)، کوکی نور تحلیلی (کرکره/پنکه/پنجره)،
> متریال triplanar/parallax/subsurface/clearcoat/sheen/matcap/displacement.
> **UI/ورودی**: RTL کامل با اعداد فارسی، rich text، اسکرول اینرسی + لیست مجازی،
> tween transform، safe-area ناچ، UI از JSON، تم قابل swap، نقشه ناوبری گیم‌پد،
> combo detection، context maps، پروفایل ورودی ماندگار، replay ورودی، تریگر آنالوگ،
> لرزش رخدادی، پخش لوکال ۲نفره.
> **شبکه**: **UDP قابل‌اطمینان** (seq/ack/resend + dedup ترتیبی) روی سیم lossy،
> delta snapshot، **client prediction + reconciliation**، interpolation موجودیت،
> lobby با کد پیوستن + host migration، replay ضبط، صف ارسال backpressure، مانیتور،
> stress تست ۳۲ کلاینت.
> **سیو**: باینری LZ4+checksum، migration نسخه‌دار، autosave سیاست‌دار + اسلات چرخشی،
> snapshot افزایشی، obfuscation ضد دستکاری.
> **VFX/DBG/SCR**: خون/دمای محیط/دی‌سالو، muzzle flash سه‌فاز، screen shake با trauma²،
> hit stop، دود با occlusion، پنل کنسول درون‌بازی، گراف FPS، توقف/قدم فریم،
> جدول هزینه سیستم‌ها، assert پروژه‌ای، مانیتور رویداد، bug report، telemetry محلی،
> hot reload اسکریپت، coroutine، sandbox مجوزها، profiler per-script، داکنگ خودکار API.
> تست: `hollow selftest` — **۱۵۹ تست** (همه سبز).

> **v2.5** (`hollow-everything`, Batch 7 — تکمیل ماتریس ۵۷۰/۵۷۰ ✅) —
> **دنیا (WLD)**: **تولید خودکار LOD چندسطحی** (decimation خوشه‌ای رأس + نرمال بازسازی + زنجیره ۶۰/۳۵/۱۵٪)،
> **Interior/Exterior Blend** (محو نرم محیط/مه/ریورب بین داخل و خارج)، **پیش‌گرمایش سلول‌های مجاور**
> در پس‌زمینه (JobQueue + کش بایت)، **streaming با بودجه حافظه** (تخلیه ارزان‌ترین: هزینه×فاصله)،
> ادغام چند صحنه در یک World، **Snapshot قطعی World** (هش FNV + JSON) برای تست regression،
> **HeadlessWorld** برای سرور/CI.
> **هوش مصنوعی**: **مسیریابی تررین پیوسته** (A* با هزینه شیب + حذف زاویه‌یابی بر مبنای شیب)،
> **پزشکی NPC** (زخمی → فرار/تقلا/بهبود با استقامت)، **کارگردان ریتم** (تنش → quiet/build/peak/release با پنجره استراحت)،
> **سناریوتست خودکار AI** با assert های ایننت/فاصله/شمارش.
> **UI/ورودی**: پنل شیشه‌مات (blur جداشدنی CPU + tint + compose آلفا)، **گیم‌پد بلوتوثی اندروید**
> (نگاشت استاندارد دکمه/استیک/تریگر + deadzone شعاعی + rumble + hot-plug).
> **شبکه**: handshake رمزی **XOR+nonce** (SHA-256 از secret+nonces، چرخش per-packet)،
> **حالت تماشاچی** (observer: دریافت snapshot بدون اثر بر شبیه‌سازی + follow دوربین)،
> **همگام‌سازی blackboard** بین کلاینت‌ها (last-write-wins با شمارنده revision)، **remote console** توکن‌دار.
> **سیو/بومی‌سازی**: ذخیره امن اندروید (پوشه داخلی + نوشتن atomic)، **قلاب ابری** (آداپتور pluggable + newest-wins + صف آفلاین)،
> **کاور اسلات** (اسکرین‌شات در فرمت HCOV با downscale)، **سیو چندبخشی** (chapter select + merge متغیر/انبار)،
> **hot-reload ترجمه** بدون ری‌استارت، **font fallback** خودکار برای گلیف گم‌شده.
> **VFX/ENV**: gore کنترل‌شده (قطعات با پرش/برخورد/رد خون)، جرقه با برخورد سطح و restitution،
> کمان تیغه (arc mesh sweep + fade)، حلقه شعبده (pulse/rune)، **پرواز گله پرنده/شب‌تاب** (boids-lite)،
> خون روی لنز (چکیده + چکیدن + تبخیر)، وینیت خون per-camera (۴ سطح bake)،
> اتاق گرم (دما/رطوبت → بخار/میعان)، پروژکتور ویدئو (cone + falloff)، **آینه planar** (انعکاس دوربین)،
> پنجره باران‌خورده (رگه‌ها + refraction)، سطح آب با انعکاس آسمان (موج دوفرکانسی + fresnel).
> **دیباگ/اسکریپت**: inspector موجودیت درون‌بازی (انتخاب/ویرایش/undo)، **replay ۶۰ثانیه‌ای ورودی** (حلقه‌ای)،
> **heat map هزینه رندر** (رامپ آبی→قرمز + ASCII)، **مانیتور نشتی هفتگی** (روند least-squares + هشدار)،
> دیباگر اسکریپت CLI (breakpoint/step)، **اتاچ دیباگر به دستگاه** (پروتکل hello/bp/step/vars قابل حمل)،
> **بایت‌کد داخلی** (کامپایل step list به op[] با constant pool + VM سریع + fallback مفسر).
> **VR کامل (۱۰ قابلیت)**: rig دوچشمی با projection جدا + IPD، **head tracking** از سنسور اندروید
> (مدل گردن)، **async reprojection سبک**، کنترلر لمسی 3DoF، کالیبراسیون IPD،
> comfort vignette هنگام چرخش، **پروفایل 90Hz**، کاهش کیفیت خودکار پله‌ای، صدای binaural VR،
> **تست headless خط لوله VR** (۹ assert).
> **ابزار توزیع (EXT)**: خروجی **itch.io** (zip + قالب صفحه HTML/MD)، **قالب CI گیت‌هاب** (build+selftest)،
> **افزونه Blender** (خروجی مستقیم .hvm)، **LSP برای FlowScript** (diagnostics روی stdio برای VSCode)،
> `hollow lint`، دیباگر CLI، **پروفایلر ریموت** (فریم JSON/GZIP + کلاینت جدولی)، **بسته single-jar** قابل توزیع.
> تست: `hollow selftest` — **۱۸۰ تست** (همه سبز) — ماتریس کامل ۵۷۰/۵۷۰.

> **v2.6** (`hollow-connected`) — netcode **Rollback** سبک GGPO (InputBuffer سه‌بخشی با کدگذاری
> افزونه‌ای + RollbackSim با rewind/resim پنجره ۷ فریمی + شمارنده‌های HUD)، **Transport/Relay**
> انتزاعی با شبیه‌ساز latency/jitter/loss، **NavMesh چندضلعی** (merge حریصانه rect).
>
> **v3.0** (`hollow-toolkit`) — پکیج `com.hollow.gameplay`: **Inventory** (انبارم/وزن/slot/کرفت)،
> **Combatant** (خط لوله آسیب + ناحیه بدن + وضعیت زمان‌دار)، **Weapon** (semi/auto/burst + خشاب)،
> **Dialogue** (شاخه‌ای + شرط + افکت)، **QuestSystem** (هدف با probe + زنجیره)، **Tutorial**.
> پکیج `com.hollow.platform`: **PluginHost** (چرخه حیات + قرنطینه کرش)، **MobileGovernor**
> (نردبان کیفیت بر اساس فریم‌تایم/باتری/دما). رندر: PlanarReflection. CLI: `hollow edit`
> (ادیتور صحنه headless) و `hollow pack` (بسته‌بندی dist). selftest: ۲۴۳ تست.
>
> **v4.0** (`aaa-core`) — **۴۰ فیکس رگرسیون واقعی** (R/P/N با چک رگرسیون در VerifyAaa) +
> ۶ سیستم AAA: **Terrain** (sculpt/splat/hole/raycast)، **Ocean** (Gerstner + شناوری)،
> **Weather** (پریست/بلند/خیس‌شدگی/رعد)، **AbilitySystem (GAS)** (tag/attribute/effect/ability)،
> **WaveDirector** (موج/بی‌پایان/JSON)، **Replay** (تراک فشرده + JSON). سوئیت `VerifyAaa` — ۵۴ چک.
>
> **v5.0** (`living-world`) — ۸ سیستم: **StealthSystem** (مخروط دید + متر کشف ۴حالته + نویز)،
> **ProgressionSystem** (XP 80·L^1.5 + درخت مهارت + respec)، **FactionSystem** (ماتریس روابط + شهرت ±100)،
> **LootSystem** (جدول وزنی + شانس + pity counter)، **EconomySystem** (فروشنده + حاشیه سود + تراکنش)،
> **SeasonsSystem** (۴ فصل + دما + طول روز)، **MusicDirector** (موزیک تطبیقی threat→intensity)،
> **MapMarkerSystem** (مارکر دسته‌ای + قطب‌نما). سوئیت `VerifyV5` — ۷۸ چک.
>
> **v6.0** (`living-world II`) — ۸ سیستم: **SurvivalSystem** (گرسنگی/تشنگی/گرما/خواب با دمای محیط و باران)،
> **FarmingSystem** (کشت + آبیاری + سرمازدگی + برداشت قطعی)، **BaseBuildingSystem** (بلوپرینت/گرید/چرخش/تعمیر)،
> **SquadCommander** (آرایش گوه/حلقه + فرمان عضوی)، **AchievementSystem**، **DifficultyDirector** (DDA)،
> **EcosystemSystem** (گیاه/شکارچی/شکار با انقراض)، **JournalSystem** (codex + یادداشت). `VerifyV6` — ۸۵ چک.
>
> **v7.0** (`civilization`) — ۸ سیستم تمدن: **SettlementSystem** (دهکده/جمعیت/انبار/کارگر/صف ساخت)،
> **CaravanSystem** (کاروان تجاری با قیمت‌گذاری کم‌بودی + غارت)، **SkillTreeSystem** (DAG + respec)،
> **DiplomacySystem** (پیمان/جنگ/صلح با آستانه رابطه)، **TerritorySystem** (رقاب کنترل منطقه + تصاحب)،
> **BountySystem** (تابلوی جایزه + شکارچی)، **NpcScheduler** (روال روزانه + شیفت شبانه)،
> **CookingSystem** (دستور پخت + کیفیت قطعی + اتصال Survival). `VerifyV7` — ۸۱ چک.
>
> **v8.0** (`kingdom`) — ۸ سیستم پادشاهی: **ResearchSystem** (درخت فناوری با دانش پژوهشگران)،
> **QuarrySystem** (معادن با ذخیره پایانه + بازتولید + از نوکارافتادن)، **TreasurySystem** (خزانه + دفتر کل + مالیات/دستمزد + ورشکستگی)،
> **MigrationSystem** (مهاجرت بین دهکده‌ها: قحطی می‌راند، فراوانی می‌کشد)، **JusticeSystem** (جنایت/سطح تعقیب/بازداشت/جریمه)،
> **AuctionSystem** (حراج با رزرو + کارمزد خانه)، **FestivalSystem** (جشنواره با boost اخلاقی)،
> **ChronicleSystem** (سالنامه تاریخ با امتیاز شهرت + بازگویی قطعی). `VerifyV8` — ۶۵ چک.
> تست کل: `hollow selftest` — **۲۰۹ تست** + ۱۱ سوئیت regression — **ALL GREEN**.
> نگهداری مستندات: از v8.0 به بعد `README.md` و `docs/*.md` با هر نسخه همگام می‌شوند.
>
> **v9.0** (`realm & saga`) — ۸ سیستم افسانه و قلمرو: **RumorSystem** (شایعه با تحریف قطعی هر hop + حافظهٔ هر NPC)،
> **RoadSystem** (جاده بین دهکده‌ها با تخفیف سفر کاروان تا ۴۵٪ + پول مهاجرت)، **TournamentSystem** (براکت حذفی قطعی + عنوان قهرمانی)،
> **GuildSystem** (گیلد با حق‌عضویت روزانه/هزینه نگهداشت/انحلال در ورشکستگی)، **SuccessionSystem** (جانشینی مشروعیت‌محور + قیام + درهم‌گسست)،
> **ExpeditionSystem** (اکسپیدیشن به ویرانه‌ها: توشه/خطر/سنگ‌قبر یا گنجینهٔ یادگار)، **PlagueSystem** (طاعون با مرگ/سرایت/قرنطینه/ایمنی)،
> **GranarySystem** (انبار غله با فساد ۲٪ روزانه + امداد خودکار در قحطی). `VerifyV9` — ۶۷ چک.
> فیکس‌های این دور: دفتر کل خزانه بعد از load معکوس می‌شد، جریمه برای شهروند بی‌سابقه پذیرفته می‌شد (۷ فیکس در ویند v8/v9).
> تست کل: `hollow selftest` — **۲۱۳ تست** + ۱۲ سوئیت regression — **ALL GREEN**.
>
> **v10.0** (`crown & coin`) — ۸ سیستم سکه و تاج: **WonderSystem** (شاهکارهای معماری با پاداش دائمی realm و انباشت پاداش‌ها)،
> **HeraldSystem** (فرمان‌های سلطنتی با مدت و lapse و جمع اثر هم‌نوع)، **CourierSystem** (نامه با تأخیر سفر و گم‌شدن قطعی پیک)،
> **WagerSystem** (شرط‌بندی روی قهرمان تورنمنت با ضرایب و سود خانه)، **DebtSystem** (وام با سود روزانه/سررسید/ورشکستگی/بازپرداخت)،
> **SpyNetworkSystem** (جاسوس با اعتبار، کشف توطئه قطعی و سوختن مأمور)، **MarketDaySystem** (روز بازار دوره‌ای با crowd و boost قیمت)،
> **LighthouseSystem** (فانوس دریایی ۳سطحی با کاهش شانس کشتی‌شکستگی و هزینهٔ نگهداشت). `VerifyV10` — ۶۴ چک.
> فیکس‌های این دور: مشروعیت حاکم زیر صفر می‌رفت، طاعون پس از پایان شمارش بیمار کهنه جا می‌گذاشت، اکسپیدیشنِ ویرانهٔ حذف‌شده بی‌رویداد گم می‌شد،
> فرمان منقضی روی load احیا می‌شد، و فانوس با شناسهٔ خالی ساخته می‌شد (۵ فیکس در ویند v9/v10).
> تست کل: `hollow selftest` — **۲۱۷ تست** + ۱۵ سوئیت regression — **ALL GREEN**.

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
| `com.hollow.game` | GameApp، Bootstrap، InputActions (مپینگ JSON)، SaveSystem (اسلات)، Localization با RTL، GameConfig — **v3+**: QuestSystem، Dialogue، Tutorial، AchievementSystem، DifficultyDirector، JournalSystem، BaseBuildingSystem، BountySystem، DiplomacySystem — **v8**: ResearchSystem، TreasurySystem، JusticeSystem، AuctionSystem، ChronicleSystem | GameMode/Enhanced Input/SaveGame |
| `com.hollow.gameplay` | **v3–v8**: Inventory، Combatant، Weapon، ProgressionSystem، FactionSystem، LootSystem، EconomySystem، SurvivalSystem، SkillTreeSystem، CookingSystem | Gameplay Framework |
| `com.hollow.world` | **v2.4+2.5**: DayNightCycle، GlobalWind، Portal، WaterDomain، TriggerZone، AudioZone + InteriorBlend، PrewarmQueue، MemoryBudget، WorldMerger، WorldSnapshot، HeadlessWorld، TemperatureVolume، VideoProjector، PlanarMirror، RainWindow، WaterSurface — **v4+**: Terrain، Ocean، Weather — **v5+**: SeasonsSystem، MapMarkerSystem — **v6+**: FarmingSystem، EcosystemSystem — **v7+**: SettlementSystem، CaravanSystem، TerritorySystem، NpcScheduler — **v8**: QuarrySystem، MigrationSystem، FestivalSystem | World/Level streaming |
| `com.hollow.ai` | Behavior Tree کامل، Blackboard، Perception، Agent — **v5+**: StealthSystem — **v6+**: SquadCommander (آرایش/فرمان) | AI/BehaviorTree/EQS |
| `com.hollow.audio` | میکسر چندباسه (master/music/sfx/ambient/voice)، صداگذاری سه‌بعدی، WAV/OGG — **v5+**: MusicDirector (موزیک تطبیقی) | MetaSounds (سبک) |
| `com.hollow.ui` | UI رتین‌مد: Panel/Label/Button/Image/Slider/Checkbox/ProgressBar/Toast، انکر لِی‌اوت، رویداد کلیک، **متن فارسی/عربی صحیح روی اندروید** (HarfBuzz خود اندروید) | UMG |
| `com.hollow.vfx` | سیستم پارتیکل CPU: rate/burst/curves (اندازه/رنگ بر عمر)، gravity/drag، additive | Niagara (سبک) |
| `com.hollow.script` | **FlowScript** — گراف رویداد→شرط→اقدام داده‌محور با ۳۰+ اکشن، زبان شرط مینی (`has_item()`, `dist_to() < 5`, `chance(0.3)`...)، EventBus با تاخیر قطعی | Blueprint (داده‌محور) |
| `com.hollow.cine` | **Sequencer** — تایم‌لاین سینماتیک + تراک انیمیشن/محیط/اسپلاین دوربین (v2.4) | Sequencer |
| `com.hollow.vr` | **v2.5**: VrRig (multi-view)، HeadTracking، AsyncReprojection، TouchController، IPD، ComfortVignette، پروفایل 90Hz، QualityScaler، BinauralAudio، HeadlessPipeline | VR |
| `com.hollow.debug` | **v2.4+2.5**: ConsolePanel، FpsGraph، FrameStepper، SystemStats، EventMonitor، Telemetry + EntityInspector، InputReplay، RenderHeatMap، LeakMonitor، RemoteProfiler | DevTools |
| `com.hollow.net` | TcpChannel + **UdpLink قابل‌اطمینان** (seq/ack/resend)، DeltaSnapshot، Prediction/Reconcile، EntityInterp، Lobby کددار، SendQueue، Monitor | Networking |
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
./hollow pose -p mygame -c walk -t 0.5    # دامپ JSON پوز انیمیشن (ANI-025)
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

مستندات بیشتر: `docs/SPEC.md` (قرارداد ماژول‌ها + پیوست سیستم‌های v2.6 تا v8.0)، `docs/CLI.md`
(مرجع خط فرمان + رگرسیون CI)، `docs/FEATURES.md` (ماتریس ۵۷۰ قابلیت + پیوست ۵۳ قابلیت نسخه‌های v2.6 تا v8.0).
رگرسیون کامل CI: `bash verify.sh` (۱۵ سوئیت + selftest — ALL GREEN).
