# :core-azahar notes

* Engine: Azahar (azahar-emu/azahar, Citra lineage) - **GPL-2.0-only**. RomCore is GPLv3. GPLv2-only code cannot be
  combined into one program with GPLv3 code. Mitigation: Azahar runs in its own `:azahar` process (separate address
  space, own native library `libcitra-android.so`), and the glue in this module talks to it only via its public
  Kotlin/JNI API. Whether a single APK containing both counts as one "work" is a legal question; get a ruling before
  distributing. Safest alternative: ship Azahar as a separate APK/side-loaded package.
* Source: `/Users/ry/Desktop/claude/azahar-src` is consumed unmodified from its working tree (composite build). Its
  `AI-POLICY.md` governs contributions *upstream* (no AI-generated contributions); it does not restrict downstream
  use. Never push azahar-src to upstream.
* The azahar-src working tree has uncommitted RomRunner-era edits (perf_stats.h frame-limiter fix, RelWithDebInfo,
  renames) that this build depends on; commit them there (locally) so they are not lost.
* Host-app manifest needs `tools:replace="android:name,android:allowBackup"` on `<application>` (Azahar's library
  manifest declares CitraApplication and allowBackup=false).
