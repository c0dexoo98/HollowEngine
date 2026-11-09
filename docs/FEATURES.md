# HollowEngine — Feature Matrix vs Unreal Engine

وضعیت صادقانه هر قابلیت. ✅ = پیاده‌سازی کامل و کارا · 🟡 = پیاده‌سازی پایه/اسکلت · 🔜 = فاز بعد · ❌ = غیرممکن در جاوا خالص (مستندشده)

## رندرینگ
| قابلیت | UE | HollowEngine |
|---|---|---|
| PBR (Cook-Torrance GGX metallic-roughness) | ✅ | ✅ GLES 3.0 |
| Deferred / Forward | ✅ | ✅ Forward (بهینه موبایل) |
| Nanite | ✅ | ❌ (نیازمند هندسه مجازی — خارج از محدوده جاوا) |
| Lumen GI / Ray Tracing | ✅ | ❌ / 🟡 (فقط raycast منطقی) |
| سایه | ✅ Virtual Shadow Maps | ✅ Directional + PCF 3x3 |
| Sky | ✅ SkyAtmosphere | ✅ رویه‌ای: گرادیان + ستاره‌های hash + ماه + هاله |
| مه | ✅ Volumetric | ✅ Exp2 + height falloff |
| Post-Process | ✅ کامل | ✅ Bloom (brightpass + gaussian) + ACES + Color Grade + Vignette + Film Grain + Chromatic Aberration + FXAA |
| Tone Mapping | ✅ | ✅ ACES + Exposure |
| IBL کامل | ✅ | 🟡 ambient hemisphere + انعکاس تحلیلی envBRDF |
| Decal | ✅ | 🔜 |
| کیفیت‌بندی خودکار | ✅ Scalability | ✅ LOW/MEDIUM/HIGH/ULTRA (رزولوشن/سایه/نور/افکت‌ها) |
| Frustum Culling | ✅ | ✅ |
| Instancing | ✅ | 🔜 |
| آب | ✅ Water System | ✅ موج Gerstner-lite + Fresnel |
| اسکینینگ GPU | ✅ | ✅ ۶۴ مفصل، ۴ weight |
| تررین | ✅ Landscape | ✅ heightmap → مش با نرمال محاسبه‌شده |

## انیمیشن
| قابلیت | UE | HollowEngine |
|---|---|---|
| لود glTF انیمیشن | ✅ | ✅ LINEAR/STEP/CUBICSPLINE (bake) |
| Crossfade بلندینگ | ✅ | ✅ دوتایی با slerp |
| State Machine | ✅ Animation Blueprint | ✅ JSON (شرط var/رویداد) |
| Blend Space | ✅ | 🔜 |
| IK / Retarget | ✅ | 🔜 |

## فیزیک
| قابلیت | UE | HollowEngine |
|---|---|---|
| برخورد استاتیک | ✅ Chaos | ✅ Box/Sphere/Capsule |
| Character Controller | ✅ | ✅ collide-and-slide + step-up + ground snap |
| Raycast | ✅ | ✅ box/sphere/capsule slab |
| تریگر + رویداد | ✅ | ✅ enter/exit با EventBus |
| Rigid Body دینامیک | ✅ | 🟡 نقطه-کره (سبک) |
| Ragdoll/Cloth/Destruction/خودرو | ✅ | 🔜 (فاز ۲) |

## هوش مصنوعی
| قابلیت | UE | HollowEngine |
|---|---|---|
| Behavior Tree | ✅ | ✅ کامل (selector/sequence/parallel/inverter/repeat/wait/condition/action/set_bb/fire_event) |
| Blackboard | ✅ | ✅ (VarTable) |
| Perception | ✅ | ✅ مخروط دید + occlusion raycast + شنوایی (رویداد noise) |
| NavMesh | ✅ | ✅ NavGrid + A* 8-dir + string-pull smoothing |
| EQS | ✅ | 🟡 (move_to/search) |
| Crowd/Mass AI | ✅ | 🔜 |

## صدا
| قابلیت | UE | HollowEngine |
|---|---|---|
| میکس چندباسه | ✅ MetaSounds | ✅ master/music/sfx/ambient/voice |
| سه‌بعدی | ✅ | ✅ equal-power pan + attenuation + rolloff |
| فرمت‌ها | ✅ | ✅ WAV (همه‌جا) + OGG (اندروید MediaCodec) |
| Reverb/EQ | ✅ | 🔜 |

## اسکریپتینگ و گیم‌پلی
| قابلیت | UE | HollowEngine |
|---|---|---|
| Blueprint بصری | ✅ | 🟡 FlowScript داده‌محور (۳۰+ اکشن + زبان شرط) — بدون ادیتور گرافیکی |
| Game Framework | ✅ GameMode/Controller | ✅ GameApp/Bootstrap/RuntimeGame |
| Enhanced Input | ✅ | ✅ InputActions + مپینگ JSON + استیک مجازی لمسی |
| Save | ✅ | ✅ اسلات JSON (vars/inventory/player/entities) |
| Localization + RTL | ✅ | ✅ + تشخیص RTL خودکار + شکل‌دهی صحیح فارسی روی اندروید |
| Sequencer سینماتیک | ✅ | ✅ دوربین/ترنسفورم/انیمیشن/صدا/رویداد/fade/caption |
| پارتیکل | ✅ Niagara | ✅ rate/burst/curve/.gravity/drag/additive |
| VFS / Asset DB | ✅ | ✅ با کش و فرمت‌های باینری اختصاصی HVM/HVT |
| شبکه | ✅ Replication کامل | 🟡 TCP channel — فاز ۲ |

## ابزارها (این‌جاست که CLI می‌درخشد)
| قابلیت | UE | HollowEngine |
|---|---|---|
| ادیتور | ✅ گرافیکی کامل | ✅ **CLI کامل** (`hollow new/gen/import/atlas/validate/tree/run/build`) + هر فایل JSON قابل ویرایش |
| تست خودکار | ✅ Gauntlet | ✅ `hollow run --assert` با شبیه‌سازی headless قطعی |
| بیلد خروجی | ✅ چند پلتفرم | ✅ APK امضاشده یک‌کلیکی |
| پروفایلر | ✅ Insights | 🟡 Stats رندر + لاگ سطح‌بندی |

## صادقانه غیرممکن در Java خالص
Nanite، Lumen کامل، Hardware Ray Tracing، MetaHuman، Path Tracer، کنسول‌ها، Pixel Streaming، ادیتور گرافیکی سه‌بعدی.

---

**نتیجه:** معماری و جریان تولید (Asset → Scene → Script → Build → Test) مطابق استاندارد Unreal طراحی شده اما در مقیاس موبایل-جاوا. هر بازی‌ای که با JSON + FlowScript + BT قابل توصیف باشد، بدون یک خط جاوا اضافه ساخته می‌شود.
