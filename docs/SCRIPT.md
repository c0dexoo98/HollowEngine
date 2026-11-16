# HollowScript — مرجع زبان

زبان اسکریپت HollowEngine 2، الهام‌گرفته از GDScript: تورفتگی به‌جای آکولاد، `func`، بدون اعلان نوع، متصل به اشیای موتور با نقطه.

## انواع مقادیر

| نوع | مثال | توضیح |
|---|---|---|
| number | `3`, `2.5`, `1e-2` | همهٔ اعداد double اند |
| string | `"سلام\n"` | escape: `\n \t \" \\` |
| bool / null | `true`, `false`, `null` | |
| list | `[1, "دو", [3]]` | `l.append(x)`, `l.pop()`, `l[i]`, `len(l)` |
| map | `{name: "hero", hp: 10}` | `m.key`, `m["key"]`, `m.key = v` (dot-set!)، `keys(m)`, `has(m, k)` |

## جملات

```python
var x = 1                # اعلان
x += 2                   # += -= *= /=
if x > 2:                # شرط + elif + else
    print("بزرگ")
elif x == 2:
    print("دو")
else:
    print("کوچک")

while x > 0:             # حلقه + break/continue
    x -= 1

for item in range(1, 5): # پیمایش: list، map (کلیدها)، string، range
    print(item)

func add(a, b):          # تابع (سطح global، بدون closure)
    return a + b
```

## عملگرها (به‌ترتیب تقدم)

`or` < `and` < `not` < `== != < <= > >=` < `+ -` < `* / %` < unary `-` < `. [] ()`

- `+` روی string: چسباندن با تبدیل خودکار (`"hp: " + 10`)
- `+`/`-` روی list: اجتماع/تفاضل
- `*` روی string×number: تکرار
- تقسیم بر صفر و باقیمانده بر صفر → خطای زمان‌اجرا

## توابع داخلی

`print` `len` `abs` `min` `max` `floor` `ceil` `round` `sqrt` `pow` `sin` `cos` `tan` `str` `num` `int` `upper` `lower` `rand` `srand` `range(a,b[,step])` `keys` `has` `append` `pop` `typeof`

**قطعیت:** `rand()` بدون seed از مسیر xorshift64* ثابت می‌گذرد؛ `srand(n)` آن را قفل می‌کند — دو اجرا خروجی یکسان می‌دهند (کل CI روی همین تکیه دارد).

## متدهای شیء

```python
"AB".lower()        "a,b".split(",")     "hi".contains("i")
l.append(x)         l.pop()              l.len()
scene.find("hero")  scene.spawn("mesh", "enemy")   scene.remove("x")
node.position_x     node.rotation_y = 45  node.visible = false
```

## نقطهٔ اتصال به موتور (Native surface)

هر گره صحنه و خود صحنه از پشتِ `Interp.Native` به اسکریپت می‌آیند:

- **Node:** `name` `type` `visible` `position_x/y/z` `rotation_x/y/z` `scale_x/y/z` `child_count`؛ متدهای `find(child)`, `child(i)`
- **Scene:** `name` `clear_color` `ambient` `node_count`؛ متدهای `find`, `spawn(type, name)`, `remove(name)`, `count`

## قلاب‌های موتور (توابعی که موتور صدا می‌زند)

| تابع | امضا | زمان |
|---|---|---|
| `on_start` | `(node, scene)` | پس از bind شدن اسکریپت |
| `on_update` | `(node, scene, delta)` | هر فریم (dt ثابت 1/60 در هدلس) |
| `on_input` | `(node, scene, key)` | هر رویداد ورودی صف‌شده |

## خطاها

- کامپایل: `HollowScript syntax error, line N: …` (IllegalArgumentException)
- اجرا: `HollowScript runtime error, line N: …` (Interp.ScriptError)
- در موتور، خطای اسکریپت هرگز فرایند را نمی‌کُشد: در Console لاگ می‌شود و بقیهٔ اسکریپت‌ها کار می‌کنند.
