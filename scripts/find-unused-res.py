#!/usr/bin/env python3
"""Find dead resources ("Leichen") in an Android app module without the SDK.

Usage: python3 find-unused-res.py [app/src/main]
Reports strings/plurals/colors/styles, drawables and layouts that are referenced
neither from Kotlin/Java (R.type.name) nor from XML (@type/name), plus strings that
exist in values-xx but not in the default values (and the other way round).
R8 already strips unused *code* from the APK, so code leftovers only cost source
readability; unused *resources* survive unless shrinkResources finds them, and
translations of removed strings stay in resources.arsc (stored uncompressed).
"""
import glob
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "app/src/main"
code = "".join(open(f, encoding="utf-8").read() for f in glob.glob(f"{root}/**/*.kt", recursive=True) + glob.glob(f"{root}/**/*.java", recursive=True))
xml = "".join(open(f, encoding="utf-8").read() for f in glob.glob(f"{root}/**/*.xml", recursive=True))
dead = []
for f in glob.glob(f"{root}/res/values/*.xml"):
    for kind, name in re.findall(r'<(string|plurals|color|style|dimen|bool|integer|array|string-array)\s+name="([^"]+)"', open(f, encoding="utf-8").read()):
        rkind = {"string-array": "array"}.get(kind, kind)
        rname = name.replace(".", "_")
        used = f"R.{rkind}.{rname}" in code or f"@{kind}/{name}" in xml or f"@{rkind}/{name}" in xml
        # style parents by naming convention (Base.AppTheme -> AppTheme) and parent="..."
        used = used or (kind == "style" and (f'parent="{name}"' in xml or f'parent="@style/{name}"' in xml or any(s.startswith(name + ".") for s in re.findall(r'name="([^"]+)"', xml))))
        if not used:
            dead.append(f"{kind}/{name}")
for f in glob.glob(f"{root}/res/drawable*/*.xml") + glob.glob(f"{root}/res/layout*/*.xml") + glob.glob(f"{root}/res/drawable*/*.png"):
    kind = "drawable" if "/drawable" in f else "layout"
    name = os.path.splitext(os.path.basename(f))[0]
    if f"R.{kind}.{name}" not in code and f"@{kind}/{name}" not in xml:
        dead.append(f"{kind}/{name} ({f})")


def names(path):
    return set(re.findall(r'<(?:string|plurals)\s+name="([^"]+)"(?![^>]*translatable="false")', open(path, encoding="utf-8").read())) if os.path.exists(path) else set()


base = names(f"{root}/res/values/strings.xml")
for loc in sorted(glob.glob(f"{root}/res/values-*/strings.xml")):
    other = names(loc)
    for n in sorted(other - base):
        dead.append(f"string/{n} only in {loc} (translation of a removed string)")
    for n in sorted(base - other):
        print(f"missing translation: {n} in {loc}")
print("\n".join(dead) if dead else "no unused resources found")
