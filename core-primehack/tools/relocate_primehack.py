#!/usr/bin/env python3
"""
Relocate a PrimeHack (shiiion/dolphin) source tree so it can live in one APK next to another
Dolphin-derived engine (:core-dolphin).  Run ONCE on a fresh copy of primehack-src:

    cp -cR primehack-src primehack-romcore-src      # APFS clone, free
    python3 relocate_primehack.py primehack-romcore-src

What it does (all renames are mechanical and total, so JNI keeps resolving):
  A. Java package  org.dolphinemu.dolphinemu  ->  com.noryan.romcore.core.primehack.jni
     (Kotlin/Java sources + directory, AndroidManifest, XML custom views, and every C++ file that has
      Java_org_dolphinemu_dolphinemu_* JNI symbols or "org/dolphinemu/dolphinemu/..." FindClass strings)
  B. Native library  libmain.so -> libprimehack_main.so   (CMake target + System.loadLibrary)
  C. File-based Android resources get a "_ph" suffix (layout/menu/xml/anim/animator/drawable/font),
     ViewBinding class names and R.<type>.<name> references follow.  Resources merge by NAME across
     all modules in an APK, so two Dolphin forks with the same layout names would otherwise silently
     override each other.
  D. Style names get a "PH" prefix (Theme.Dolphin.Main -> PHTheme.Dolphin.Main) for the same reason.
Value resources (strings/colors/dimens/arrays/ids) are left alone: same names, same meaning.
"""
import os, re, sys

root = os.path.abspath(sys.argv[1])
android = os.path.join(root, "Source", "Android")
res = os.path.join(android, "app", "src", "main", "res")
sentinel = os.path.join(root, ".romcore-relocated")
if os.path.exists(sentinel):
    sys.exit("already relocated")

OLD_PKG, NEW_PKG = "org.dolphinemu.dolphinemu", "com.noryan.romcore.core.primehack.jni"
OLD_PATH, NEW_PATH = OLD_PKG.replace(".", "/"), NEW_PKG.replace(".", "/")
OLD_JNI, NEW_JNI = OLD_PKG.replace(".", "_"), NEW_PKG.replace(".", "_")

TEXT_EXT = {".kt", ".java", ".xml", ".cpp", ".h", ".txt", ".kts", ".pro", ".cmake", ".json", ".gradle"}
SKIP_DIRS = {"build", ".cxx", ".git", "benchmark", "Externals"}

def walk(base):
    for d, dirs, files in os.walk(base):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for f in files:
            yield os.path.join(d, f)

def is_text(p):
    return os.path.splitext(p)[1] in TEXT_EXT or os.path.basename(p) == "CMakeLists.txt"

def rewrite(path, fn):
    try:
        s = open(path, encoding="utf-8").read()
    except (UnicodeDecodeError, OSError):
        return
    n = fn(s)
    if n != s:
        open(path, "w", encoding="utf-8").write(n)

# ---------- A. package ----------
scan_roots = [os.path.join(root, "Source", "Android"), os.path.join(root, "Source", "Core")]
for base in scan_roots:
    for p in walk(base):
        if is_text(p):
            rewrite(p, lambda s: s.replace(OLD_PKG, NEW_PKG).replace(OLD_PATH, NEW_PATH).replace(OLD_JNI, NEW_JNI))
old_dir = os.path.join(android, "app", "src", "main", "java", *OLD_PKG.split("."))
new_dir = os.path.join(android, "app", "src", "main", "java", *NEW_PKG.split("."))
os.makedirs(os.path.dirname(new_dir), exist_ok=True)
os.rename(old_dir, new_dir)
# remove now-empty org/dolphinemu
for d in (os.path.dirname(old_dir), os.path.dirname(os.path.dirname(old_dir))):
    try: os.rmdir(d)
    except OSError: pass

# ---------- B. library name ----------
jni_cm = os.path.join(android, "jni", "CMakeLists.txt")
rewrite(jni_cm, lambda s: re.sub(r"\badd_library\(main\b", "add_library(primehack_main", s)
         .replace("target_link_libraries(main\n", "target_link_libraries(primehack_main\n")
         .replace("target_include_directories(main\n", "target_include_directories(primehack_main\n")
         .replace("CPACK_PACKAGE_EXECUTABLES main)", "CPACK_PACKAGE_EXECUTABLES primehack_main)"))
rewrite(os.path.join(new_dir, "DolphinApplication.kt"),
        lambda s: s.replace('System.loadLibrary("main")', 'System.loadLibrary("primehack_main")'))

# ---------- C. file-based resources ----------
FILE_TYPES = {"layout": "layout", "menu": "menu", "xml": "xml", "anim": "anim", "animator": "animator",
              "drawable": "drawable", "font": "font"}
renamed = {}   # type -> set(names)
for entry in sorted(os.listdir(res)):
    t = entry.split("-")[0]
    if t not in FILE_TYPES:
        continue
    d = os.path.join(res, entry)
    for f in sorted(os.listdir(d)):
        base, ext = os.path.splitext(f)
        if base.endswith(".9"):
            base, ext = base[:-2], ".9" + ext
        renamed.setdefault(t, set()).add(base)
        os.rename(os.path.join(d, f), os.path.join(d, base + "_ph" + ext))

def camel(n):
    return "".join(w[:1].upper() + w[1:] for w in n.split("_"))

res_files = [p for p in walk(os.path.join(android, "app", "src", "main")) if is_text(p)]
# value-defined drawables aliases etc. are rare; refs are rewritten wherever the name matches a renamed file.
def fix_res(s):
    for t, names in renamed.items():
        alt = "|".join(sorted((re.escape(n) for n in names), key=len, reverse=True))
        s = re.sub(r"(@%s/)(%s)(?![\w])" % (t, alt), r"\1\2_ph", s)
        s = re.sub(r"(\bR\.%s\.)(%s)(?![\w])" % (t, alt), r"\1\2_ph", s)
        s = re.sub(r"(\bandroid:%s=\"@%s/)" % (t, t), r"\1", s)
        if t == "layout":
            balt = "|".join(sorted((re.escape(camel(n)) for n in names), key=len, reverse=True))
            s = re.sub(r"\b(%s)Binding\b" % balt, r"\1PhBinding", s)
    return s
for p in res_files:
    rewrite(p, fix_res)

# ---------- D. style names ----------
style_names = set()
for p in walk(res):
    if p.endswith(".xml") and "/values" in p:
        style_names |= set(re.findall(r'<style\s+name="([^"]+)"', open(p, encoding="utf-8").read()))
alt_x = "|".join(sorted((re.escape(n) for n in style_names), key=len, reverse=True))
def fix_style_xml(s):
    s = re.sub(r'(<style\s+name=")(%s)(")' % alt_x, r"\1PH\2\3", s)
    s = re.sub(r'(parent=")(%s)(")' % alt_x, r"\1PH\2\3", s)
    s = re.sub(r"(@style/)(%s)(?![\w.])" % alt_x, r"\1PH\2", s)
    return s
alt_k = "|".join(sorted((re.escape(n.replace(".", "_")) for n in style_names), key=len, reverse=True))
def fix_style_code(s):
    return re.sub(r"(\bR\.style\.)(%s)(?!\w)" % alt_k, r"\1PH\2", s)
for p in res_files:
    if p.endswith(".xml"):
        rewrite(p, fix_style_xml)
    elif p.endswith((".kt", ".java")):
        rewrite(p, fix_style_code)

open(sentinel, "w").write("relocated by relocate_primehack.py\n")
print("done; renamed resource types:", {k: len(v) for k, v in renamed.items()}, "styles:", len(style_names))
