#!/usr/bin/env python3
"""Fix the eager nextId++ default-argument bug in load() across the engine.

Java evaluates arguments eagerly, so (int)Json.num(m.get("id"), nextId++)
increments nextId once per restored record even when the saved id is present
-- reloading N records inflates nextId by N and breaks id continuity.
Replace the fragment with a nextIdFor() helper and insert the helper into
every affected class, right before its persistence section. Idempotent.
"""
import re, sys

FILES = [
    "engine/src/main/java/com/hollow/world/GuildSystem.java",
    "engine/src/main/java/com/hollow/world/CaravanSystem.java",
    "engine/src/main/java/com/hollow/world/FestivalSystem.java",
    "engine/src/main/java/com/hollow/game/CarpenterSystem.java",
    "engine/src/main/java/com/hollow/game/DebtSystem.java",
    "engine/src/main/java/com/hollow/game/CourierSystem.java",
    "engine/src/main/java/com/hollow/game/BlacksmithSystem.java",
    "engine/src/main/java/com/hollow/game/ChronicleSystem.java",
    "engine/src/main/java/com/hollow/game/AuctionSystem.java",
    "engine/src/main/java/com/hollow/game/RumorSystem.java",
    "engine/src/main/java/com/hollow/game/TournamentSystem.java",
    "engine/src/main/java/com/hollow/game/BountySystem.java",
    "engine/src/main/java/com/hollow/game/HeraldSystem.java",
    "engine/src/main/java/com/hollow/game/ExpeditionSystem.java",
    "engine/src/main/java/com/hollow/game/WagerSystem.java",
]

FRAGMENT = '(int)Json.num(m.get("id"), nextId++)'
REPLACEMENT = 'nextIdFor(m.get("id"))'
HELPER = (
    "    /** saved id if present; otherwise allocate from nextId (never inflates on load) */\n"
    "    private int nextIdFor(Object raw){\n"
    "        int saved = (int)Json.num(raw, 0);\n"
    "        return saved > 0 ? saved : nextId++;\n"
    "    }\n\n"
)
ANCHOR = "    // ---------------- persistence ----------------"

changed = 0
for path in FILES:
    with open(path, encoding="utf-8") as f:
        src = f.read()
    orig = src
    n = src.count(FRAGMENT)
    has_helper = "private int nextIdFor(Object raw)" in src
    if n == 0 and has_helper:
        print(f"skip (already fixed): {path}")
        continue
    src = src.replace(FRAGMENT, REPLACEMENT)
    if "private int nextIdFor(Object raw)" not in src:
        idx = src.find(ANCHOR)
        if idx < 0:
            print(f"WARN no anchor: {path}")
            sys.exit(1)
        src = src[:idx] + HELPER + src[idx:]
    with open(path, "w", encoding="utf-8") as f:
        f.write(src)
    changed += 1
    print(f"fixed {n} occurrence(s): {path}")

print(f"--- {changed} file(s) changed")
