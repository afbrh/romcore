# RomCore architecture contract (all agents read this first)

Goal: one Android app (com.noryan.romcore, GPLv3, repo afbrh/romcore) that scans a user-chosen ROM folder,
picks the right embedded "Core" for each file, and runs it with ONE shared controller mapper and ONE shared
in-game menu. Main menu looks like RomRunner (/Users/ry/Desktop/claude/romrunner — read its ui/theme and ui/library).
Reference prior art (read, do not edit, do not copy wholesale): /Users/ry/Desktop/claude/romrunner-builtinemulators
(its commits show how each emulator was embedded; sources in /Users/ry/Desktop/claude/*-src are already patched for it).
Fresh project, so build clean modules; reuse hard-won knowledge (lib-name collisions, authorities, etc).

## Modules (each agent OWNS exactly its module; do not edit others — message the lead for api changes)
- :romcore-api   Shared contract (Contract.kt) + shared in-game menu composables + InputProfile/mapper + hotkeys.
- :app           Shell: RomRunner-style UI, ROM scanner, routing, settings, core registry bootstrap.
- :core-<id>     One per emulator: retroarch, ppsspp, dolphin, primehack, cemu, eden, azahar, watermelonds, armsx2.

## Hybrid packaging rule
- Libretro-capable engines run inside :core-retroarch as bundled .so cores (in-process).
- Everything else is a standalone engine embedded in its own :core-<id> module, its game Activity in its own
  android:process (":dolphin" etc.) so native symbols/GL contexts never collide. Native libs that share a
  filename (e.g. libmain.so) MUST be renamed or side-loaded; no two modules may ship the same .so name.
- Per-core decision (agent may justify a change in its report): retroarch=host for 8/16-bit, N64, GB/GBA, PS1, Genesis etc;
  ppsspp=standalone engine (libretro only as fallback); dolphin(GC/Wii), primehack(Metroid Prime trilogy, preferred for those),
  cemu(Wii U; prebuilt SSimco APK https://github.com/SSimco/Cemu/releases may be used as binary source if building fails;
  source also at /Users/ry/Desktop/claude/cemu-src), eden(Switch), azahar(3DS), watermelonds(NDS), armsx2(PS2).

## Public API (see romcore-api/.../Contract.kt)
CoreDescriptor (id, systems, extensions, launchIntent) registered into CoreRegistry; CoreSession (pause/resume/
saveState/loadState/close/injectButton/injectAxis) implemented by each core Activity. Each :core-<id> exposes
`object <Id>Core : CoreDescriptor` and `fun register()`; :app calls all register()s at startup.

## Input rules (owned by :romcore-api input agent)
Canonical Pad = label on physical button. Nintendo: A-A,B-B,X-X,Y-Y. PS1/PS2/PSP: A=Circle,B=Cross,X=Triangle,Y=Square
(PsFace.of). Auto-map known handhelds (AYN Thor, Odin, Retroid, Anbernic, generic XInput) by InputDevice descriptor/name.
Every core Activity routes dispatchKeyEvent/dispatchGenericMotionEvent through romcore-api InputRouter FIRST.

## In-game menu (owned by :romcore-api menu agent, hosted by every core Activity)
Exactly: Close Game, Change Controller Mapping, Save State, Load State, Set Hotkeys. Opened by the Back button
(default Open Menu hotkey; configurable, Back always stays as fallback). Nothing else opens in-game. D-pad navigable, no popups (inline fold-down panels, "tabbing down" style), style from
/Users/ry/Desktop/claude/romrunner-builtinemulators/menu-style-reference. Pause the core while open.

## Build rules
Android SDK /Users/ry/Library/Android/sdk, NDK 27.3.13750724 / 28.x / 29 available, JDK17 /Users/ry/android-dev/jdk-17.0.20.1+1,
AGP 9.2.1, Kotlin 2.4.0 built-in, compileSdk 37, minSdk 30, arm64-v8a only. Target device: AYN Thor (adb installed).
Never push to upstream remotes of *-src repos (origin is the real project). Do not push romcore anywhere until lead says.
Do NOT commit ROMs, BIOS, keys, firmware. Uncommitted work is not done: commit locally in /Users/ry/Desktop/claude/romcore
(only your module's paths). Report: what builds, what's untested on device, license notes, blockers.
