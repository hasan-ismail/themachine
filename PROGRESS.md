# PROGRESS

Running record of the build. Written so a future session can resume cold: what is done, what
was decided and why, and which facts were verified against a live source rather than assumed.

**Last updated:** 2026-09-02 (second session)

## Phase status

| Phase | Scope | Status |
|---|---|---|
| P0 | Repo + toolchain | **Complete** — gate green locally and in CI; APK verified on device |
| P1 | Native bridge (whisper.cpp + llama.cpp submodules, JNI) | **Complete** — both engines build, link and report on device |
| P2 | Model manager (registry, resumable verified downloads) | **Complete** — three assets download, verify and load on device |
| P3 | STT (AudioRecord, endpointing, Whisper) | **Complete** — plus live partial transcripts while speaking |
| P4 | LLM parsing (GBNF grammar, TimeResolver, tool calls) | **Complete** — 14 tools, grammar-constrained, verified on device |
| P5 | TTS (sherpa-onnx + Piper) | **Complete** — replies and problems are spoken |
| P6 | Executors + task store | **Mostly done** — all 14 tools dispatch; tasks persist as markdown, not Room (see below) |
| P7 | Assistant session + onboarding | **Mostly done** — side button opens a full session end to end |
| P8 | Settings + release | Partly — settings screens exist; signed release flow untested |

### What the assistant does now

Commands run from the side button or by typing. A phrase whose meaning is fixed is
learned on first success and runs instantly ever after, with no model involved. Questions
go to the larger model; everything else is decided by the small one in a single call.
Reminders fire, survive reboots and clock changes, and answer Done and Snooze. The screen
is read from the accessibility tree, or from pixels when the tree is empty. Every command
is recorded, on the device, in a file the user can read.

### Measured on the reference device (Galaxy Z Flip8, SM8850, thermally throttled)

| | Before | Now |
|---|---|---|
| Reply, first command of a session | 14097 ms | **923 ms** |
| Reply, later commands | ~13000 ms | **600–1900 ms** |
| Prompt prefill | ~13000 ms | ~450 ms reused, ~0 from disk cache |

27 instrumented tests pass, including three that run synthesised speech through the
whole pipeline and one that says a sentence out loud.

---

## P0 — Repo + toolchain

### What exists

- Single-module Gradle build (`:app`), package `io.github.hasanismail.themachine`.
- Compose + Material 3 shell: `TheMachineApplication` (Hilt), `MainActivity`, theme, adaptive
  launcher icon with a themed monochrome layer.
- Version catalog, Spotless (ktlint), detekt, Android lint with `warningsAsErrors`.
- `ci.yml` (build/lint/test + a privacy-invariant check) and `release.yml` (signed APK on a
  `v*` tag). AGPL-3.0 `LICENSE`, issue templates, PR template, `dependabot.yml`.

### Verified

```
./gradlew spotlessCheck detekt testDebugUnitTest lintDebug assembleDebug   → BUILD SUCCESSFUL
```

Debug APK: 30.3 MB (unminified Compose + Hilt).

- **CI green** on `main` — both jobs pass in 6m36s, APK and reports uploaded as artifacts.
- **Runs on real hardware.** Installed and launched on the Z Flip8 (below): no crash, dark
  theme and dynamic colour applied, edge-to-edge insets correct.

### Test device

Wireless ADB, paired and connected:

| | |
|---|---|
| Device | Galaxy Z Flip8 (`SM-F776U1`, `b8q`) |
| Android | 17, **API 37** — the same level the app targets |
| SoC / cores | Snapdragon 8 Elite Gen 5 (`SM8850`), 8 cores |
| RAM | 11.35 GB — comfortable even for Gemma 3 4B |
| ABI list | `arm64-v8a` **only** (no 32-bit at all) |
| `getconf PAGE_SIZE` | **4096** — this device is *not* a 16 KB device |
| Free storage | 81 GB |

```bash
adb pair 192.168.4.131:<pairing-port> <code>   # pairing port + code from the pairing dialog
adb connect 192.168.4.131:44983                # connect port from the main screen — a different port
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.hasanismail.themachine.debug/io.github.hasanismail.themachine.ui.MainActivity
```

The wireless-debugging port changes whenever debugging is toggled or the phone reconnects to
Wi-Fi, so re-pair rather than assuming 44983 still works. mDNS discovery (`adb mdns services`)
returns nothing on this Windows host, so the connect port has to come off the phone's screen.

> The 4 KB page size is **this** device. The reference S26 Ultra may well be a 16 KB device, so
> the alignment work in P1 still has to happen and has to be verified on that phone specifically.
> A screenshot is worth capturing with `adb shell screencap -p /sdcard/x.png && adb pull`;
> piping `exec-out screencap` through PowerShell corrupts the binary.

### Toolchain — every version resolved against a live source on 2026-09-01

Resolved from Maven metadata (`maven.google.com`, `repo1.maven.org`), the Gradle services
API, the live Android SDK manifest, and the GitHub Releases API. Nothing here is from memory.

| Thing | Version | Source of truth |
|---|---|---|
| AGP | 9.4.0 | `com.android.tools.build:gradle` maven-metadata |
| Gradle | 9.6.1 | AGP 9.4.0's declared min **and default** |
| Kotlin | 2.4.10 | `kotlin-gradle-plugin` maven-metadata (latest stable) |
| KSP | 2.3.11 | `symbol-processing-gradle-plugin` maven-metadata |
| JDK (runs Gradle) | 21 | Microsoft OpenJDK 21.0.12.1 |
| JVM target (bytecode) | 17 | AGP 9.4.0 default |
| compileSdk / targetSdk | 37 | AGP 9.4.0 caps at API 37 |
| minSdk | 33 | `CLAUDE.md` locked decision |
| Build tools | 36.0.0 | AGP 9.4.0 min and default |
| NDK | 29.0.14206865 | latest stable (deviation, see below) |
| CMake | 3.31.6 | see "CMake 4.x" below |
| Compose BOM | 2026.08.00 | `androidx.compose:compose-bom` maven-metadata |
| Hilt | 2.60.1 | `com.google.dagger:hilt-android` maven-metadata |
| androidx.hilt | 1.4.0 | maven-metadata |
| Spotless / ktlint | 8.10.1 / 1.8.0 | maven-metadata |
| detekt | 1.23.8 | latest **stable** (2.x is alpha only) |
| Robolectric | 4.16.1 | maven-metadata |

GitHub Actions — every tag confirmed against the GitHub Releases API, not assumed:

`actions/checkout@v7.0.1` · `actions/setup-java@v6.0.0` · `gradle/actions/setup-gradle@v6.3.0`
· `actions/cache@v6.1.0` · `actions/upload-artifact@v7.0.1` · `actions/download-artifact@v8.0.1`
· `softprops/action-gh-release@v3.0.3`

> Node 20 is removed from GitHub runners on **2026-09-23**. All of the above are Node 24-based.
> `upload-artifact@v7` pairs with `download-artifact@v8` — the majors are deliberately offset.

### Decisions and deviations

1. **AGP 9 removed the `org.jetbrains.kotlin.android` plugin.** AGP 9 compiles Kotlin itself
   (`android.builtInKotlin`, default on) and *fails the build* if that plugin is applied. AGP
   bundles KGP 2.2.10; the compose/serialization/KSP plugin markers pull 2.4.10 onto the
   shared buildscript classpath, which wins by normal conflict resolution. Verified by reading
   the resolved buildscript classpath: `kotlin-gradle-plugin:2.2.10 -> 2.4.10`.

2. **Gradle 9.6.1, not the latest 9.7.1.** 9.7.1 is the current stable and it did build this
   project, but AGP 9.4.0 declares Gradle 9.6.0 as both its minimum *and* its default, so
   9.6.x is the band Google actually tests AGP 9.4.0 against. Chose the tested pairing.

3. **NDK r29 (29.0.14206865), against AGP's default of 28.2.13676358.** Two reasons: the
   current `android sdk install` CLI silently refuses to side-install an older NDK once r29 is
   present (exits 0, installs nothing — confirmed across three invocations and syntaxes); and
   r29 was verified here to cross-compile an `arm64-v8a` shared library with Clang 21. AGP's
   "default NDK" is what it picks when you don't specify one, not a ceiling. **Revisit in P1**
   if whisper.cpp or llama.cpp hit an r29 incompatibility.

4. **detekt 1.23.8 (stable), not 2.0.0-alpha.6.** Research suggested no stable detekt could
   work on this stack. That is **false**: detekt 1.23.8 runs clean under AGP 9.4.0 + Gradle
   9.6.1 + Kotlin 2.4.10 here, and was proven to actually bite (see below). Residual risk:
   1.23.8 parses with an older Kotlin frontend, so genuinely new 2.4 syntax could trip it. The
   fallback is `dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6` — an alpha in a required CI
   gate, which is why it is the fallback and not the default.

5. **detekt was silently analysing zero files.** The base detekt plugin only knows the JVM
   convention (`src/main/kotlin`), so on an Android module it produced an empty report and a
   green build — a gate that could never fail. Fixed with `source.setFrom(projectDir/src)`.
   Verified by injecting a file with an empty function and a swallowed exception: detekt found
   all three violations and failed the build. **Re-verify this way after touching detekt config.**

6. **ktlint style is `intellij_idea`, not ktlint's own `ktlint_official`.** The official style
   rewrites `libs.versions.compileSdk.get().toInt()` into a four-line broken chain, making
   every version-catalog reference unreadable. `intellij_idea` also matches the
   `kotlin.code.style=official` already set in `gradle.properties`.

7. **Coordinate corrections.** `androidx.core:core-ktx:1.19.0` is now an **empty shim** —
   a 5.5 KB AAR whose `classes.jar` is 183 bytes and whose only job is to depend on
   `androidx.core:core`. Same pattern for `androidx.work:work-runtime-ktx`. The catalog uses
   the real artifacts. `androidx.arch.core:core-testing` was dropped: it exists for
   `InstantTaskExecutorRule` (LiveData), and this project is Flow-only.

8. **Permissions are declared per phase**, as the feature that needs them lands, so the
   manifest always describes what the app actually does. The exhaustive v1 list is in
   `CLAUDE.md`.

9. **Lint suppressions**, each deliberate: `GradleDependency` / `AndroidGradlePluginVersion` /
   `NewerVersionAvailable` (version bumps go through Dependabot PRs, so "newer exists" must
   not fail a build) and `ChromeOsAbiSupport` (arm64-only is the locked scope).

### Local machine notes (Windows dev box — not part of the repo)

- **Smart App Control is enforcing** (`HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy` →
  `VerifiedAndReputablePolicyState = 1`). It blocks the **unsigned** `aapt2.exe` that AGP
  downloads from Google's Maven, with the misleading message "check if you installed the
  Windows Universal C Runtime" (the runtime is installed and fine). The copy in the Android
  SDK build-tools is signed by Google LLC and runs. Worked around in the **user-level**
  `~/.gradle/gradle.properties`, deliberately not in the repo:
  ```properties
  android.aapt2FromMavenOverride=C:/Android/Sdk/build-tools/37.0.0/aapt2.exe
  ```
  Do not disable Smart App Control to "fix" this — it cannot be re-enabled without a Windows
  reinstall.
- **Forward slashes in every `.properties` value.** `java.util.Properties` treats `\` as an
  escape, so `sdk.dir=C:\Android\Sdk` silently becomes `C:AndroidSdk` and AGP fails with
  "The filename, directory name, or volume label syntax is incorrect". Android lint's
  `PropertyEscape` also flags it; `local.properties` uses `sdk.dir=C\:/Android/Sdk`.
- SDK at `C:\Android\Sdk`, JDK 21 at `C:\Android\jdk-21`. The project path contains a space
  (`C:\Users\Hasan Ismail\...`); this was explicitly tested and the NDK/CMake/Ninja toolchain
  handles it — a probe `arm64-v8a` `.so` built cleanly from that path.

### Pending human action

- Nothing blocking. Release signing secrets are only needed at P8 (README documents the setup).

---

## P1 — Native bridge

### What exists

- `whisper.cpp` pinned at **v1.9.3** (`371b5a75…`) and `llama.cpp` at **v0.3.0** (`c1d0e7a0…`)
  as submodules under `app/src/main/cpp/`. Both commit hashes were confirmed against the
  upstream tags rather than taken on trust.
- `app/src/main/cpp/CMakeLists.txt` builds both engines with ARM runtime feature dispatch,
  plus `libthemachine.so`, a thin JNI bridge.
- `NativeBridge.kt` loads the library and registers ggml's backends once, process-wide.
- A debug-only `DebugActivity` (in the `debug` source set, so it cannot ship in release)
  printing versions, backend registry, CPU features and page size.

### Verified on device

`./gradlew deviceTest` → `instrumentation: OK (6 tests)` on the Flip8, and the debug screen
reports:

| | |
|---|---|
| whisper.cpp | 1.9.3 |
| llama.cpp | 0.3.0 |
| ggml backends | 1 registry, 1 device (CPU, 10.8 GiB) |
| CPU features picked | `NEON`, `ARM_FMA`, `FP16_VA`, `MATMUL_INT8` (i8mm), `SVE`, `SVE_CNT=16`, `SME`, `DOTPROD`, `REPACK` |
| mmap | supported |

All **16** packaged `.so` files are 16 KB-page aligned (checked with `llvm-readelf -l`,
minimum `LOAD` `p_align` = `0x4000`). The release build was also verified: R8 plus resource
shrinking keep **all seven** `libggml-cpu-android_armv*.so` variants, so runtime dispatch
survives minification. Release APK is 7.6 MB.

### Decisions and findings

1. **Dual ggml, resolved by ordering.** whisper.cpp v1.9.3 vendors ggml 0.20.2 and
   llama.cpp v0.3.0 vendors 0.22.0. Both add ggml behind `if (NOT TARGET ggml)`, so whichever
   is added first supplies it to the other. **llama.cpp is added first** so the newer ggml
   wins. Verified safe rather than assumed: diffing the vendored trees, no public header is
   added or removed and only `ggml.h` differs (24 lines). The one semantic change —
   `ggml_clamp` losing its in-place behaviour to a new `ggml_clamp_inplace` — does not matter
   because whisper.cpp never references `ggml_clamp`. Escape hatch if a future bump breaks
   this: whisper's `WHISPER_USE_SYSTEM_GGML`.

2. **`extractNativeLibs=true`, reversing the P0 default.** ggml's backend loader enumerates a
   real directory with `std::filesystem::directory_iterator`, scoring each
   `libggml-cpu-*.so`. With libraries left inside the APK there is nothing to enumerate:
   on device it registered **zero backends and reported success**, which would have meant
   inference silently having no backend to run on. llama.cpp's own Android example sets
   `extractNativeLibs="true"` for exactly this reason. Costs ~15 MB of install size. 16 KB
   support is unaffected — that is about ELF segment alignment, not zip alignment.

3. **The `common` target is called `llama-common`.** It carries `json_schema_to_grammar`,
   which P4 needs, so `LLAMA_BUILD_COMMON=ON` is required rather than optional.

4. **`connectedDebugAndroidTest` is broken against this device.** AGP writes
   `test-result-exit-code.txt = 1` and fails the task while its own HTML report says
   "6 tests, 0 failures, 100% successful", and `am instrument` run by hand prints
   `OK (6 tests)`. Reproduced with a single trivial test that touches nothing native, so it
   is independent of test content — an AGP/One UI interaction. Rather than leave a
   permanently red gate, `./gradlew deviceTest` runs the instrumentation over adb and
   believes the runner's own verdict. Proven to bite: injecting a bad assertion produced
   `FAILURES!!! | Tests run: 6, Failures: 1` and failed the build.

5. **Spotless and detekt now exclude `**/cpp/**`.** The submodules bring tens of thousands of
   upstream files including Kotlin sample apps; without the exclusion both tools tried to
   analyse vendored code.

---

## Verified facts held for later phases

Gathered during P0 research and cross-checked; recorded here so the next session does not
re-derive them. Anything marked **recompute** must be re-verified before it is committed to code.

### P1 — native

| Item | Value |
|---|---|
| whisper.cpp | `https://github.com/ggml-org/whisper.cpp` (the `ggerganov` URL 301-redirects), tag **`v1.9.3`**, annotated → commit `371b5a7561823ab2bb32142d2751e35e7534727b` |
| llama.cpp | `https://github.com/ggml-org/llama.cpp`, tag **`v0.3.0`**, annotated → commit `c1d0e7a004015f23bc0233470b747b596f29b264` |

- **llama.cpp now uses semantic versioning.** The old `bNNNN` tags are nightlies cut several
  times a day and flagged `prerelease=true` — never pin one. Only five semver tags exist
  (`v0.1.0`…`v0.3.0`). Major is still 0, so *any* minor bump may break the C API.
- **Do not script off `releases/latest` for whisper.cpp** — it resolves to the nightly `b4938`.
  GitHub flags `v1.9.3` as a prerelease; that is a one-off CI mislabel from its first
  `make-release.yml` cut, since corrected upstream.
- ARM runtime feature dispatch (what `CLAUDE.md` asks for) is the trio
  `BUILD_SHARED_LIBS=ON` + `GGML_BACKEND_DL=ON` + `GGML_CPU_ALL_VARIANTS=ON`, which must be set
  together and require `GGML_NATIVE=OFF` (a hard `FATAL_ERROR` otherwise). On Android this
  produces seven `dlopen`ed `libggml-cpu-android_armv*.so` variants — v8.0, v8.2+dotprod,
  v8.2+dotprod+fp16, v8.6+i8mm, v9.0 SVE2, v9.2 SVE+SME, v9.2_2 — scored at runtime.
  Also `GGML_OPENMP=OFF` (NDK OpenMP is not CMake-installable) and `GGML_LLAMAFILE=OFF`
  (no Android support). llama.cpp additionally needs `LLAMA_BUILD_COMMON=ON`.
- **Two remembered llama.cpp APIs are wrong.** There is no `json_schema_to_gbnf`; it is
  `json_schema_to_grammar(const common_json&, bool force_gbnf = false)` in
  `common/json-schema-to-grammar.h` — which lives in the `common` target, hence
  `LLAMA_BUILD_COMMON=ON`. Feed its output to `llama_sampler_init_grammar()`. For the prompt
  cache, the live pair is `llama_state_save_file` / `llama_state_load_file`
  (`llama_save_session_file` and friends are deprecated aliases). `llama_load_model_from_file`,
  `llama_new_context_with_model` and `llama_n_vocab` are all deprecated in favour of
  `llama_model_load_from_file`, `llama_init_from_model` and `llama_vocab_n_tokens`.
- **Open risk — dual ggml.** whisper.cpp v1.9.3 vendors ggml 0.20.2, llama.cpp v0.3.0 vendors
  0.22.0. Both define targets named `ggml`/`ggml-base`/`ggml-cpu` guarded by `if (NOT TARGET ggml)`,
  so whichever is added first wins and the other links against a different ggml than it was
  written for. This has been reasoned about but never compiled. v1.9.3 exposes
  `WHISPER_USE_SYSTEM_LLAMA` (which auto-promotes `WHISPER_USE_SYSTEM_GGML`), which is the
  likeliest way out. **Resolve this first in P1.**
- **CMake stays at 3.31.6, deliberately.** whisper.cpp still declares
  `cmake_minimum_required(VERSION 3.5)`, sitting exactly on CMake 4.x's removed-compatibility
  floor with zero headroom. 4.1.2 is installed locally as a fallback only.
- **16 KB page size is a device requirement, not a Play policy** — without 16 KB-aligned `.so`
  the app simply will not run on a 16 KB device, which the S26 Ultra likely is. Satisfied by
  NDK ≥ r28 (aligned by default) plus `jniLibs.useLegacyPackaging = false` (already set).
  Verify on the release APK rather than assuming, because a third-party prebuilt (the
  sherpa-onnx AAR) can still be unaligned. In native code, `PAGE_SIZE` is **undefined** on
  NDK r27+ — use `sysconf(_SC_PAGESIZE)`. This matters: both engines `mmap` their models.
- Model the JNI surface on `examples/whisper.android/lib/src/main/jni/whisper/jni.c`, but do
  **not** copy that example's Gradle config (compileSdk 34, minSdk 26, NDK r25, Java 8 — all
  badly stale). Same for `examples/llama.android`.

### P2 / P5 — assets

| Asset | URL | Size |
|---|---|---|
| sherpa-onnx AAR | `.../releases/download/v1.13.7/sherpa-onnx-1.13.7.aar` | 49,113,869 B |
| Piper voice | `.../releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2` | 67,223,746 B |

Both under `https://github.com/k2-fsa/sherpa-onnx`. URLs re-verified HTTP 200 with matching
`Content-Length`; the **SHA-256 values must be recomputed** before they go into the registry.

- **sherpa-onnx has no official Maven coordinate.** Two artifacts on Maven Central are traps:
  `net.dreamlu:mica-sherpa-onnx` (desktop JVM natives, no Android ABIs) and
  `com.bihe0832.android:lib-sherpa-onnx` (unaffiliated third-party wrapper). Neither is from
  k2-fsa. Plan: a Gradle task that downloads the pinned AAR and verifies its SHA-256 before
  the build proceeds — same checksum discipline as the runtime model downloads.
  Consider `sherpa-onnx-static-link-onnxruntime-1.13.7.aar` for a single-ABI app.
- The Piper tag is the literal string `tts-models` (an asset-hosting tag, not a version), so
  that URL is stable across sherpa-onnx releases. Extracted layout:
  `en_US-amy-medium.onnx` (63.2 MB), `.onnx.json`, `tokens.txt`, `MODEL_CARD`, `espeak-ng-data/`.
  Wire as `OfflineTtsVitsModelConfig(model=…onnx, tokens=…tokens.txt, dataDir=…espeak-ng-data)`.
  22050 Hz, 1 speaker. Consider the int8 variant (21.0 MB, 3.2× smaller) for the latency budget.
- **`CLAUDE.md`'s "~65 MB" TTS figure is the compressed tarball.** Budget ~81 MB extracted, and
  ~148 MB transient if the tarball is kept during extraction. Factor into the storage preflight.
- **Foreground-service timeout.** At `targetSdk` ≥ 35 an app gets 6 hours of `dataSync`
  foreground service per 24 hours, shared across all such services, then `Service.onTimeout()`
  fires and failing to stop promptly throws a fatal `RemoteServiceException`. Implement
  `onTimeout`. This makes resumable HTTP Range downloads load-bearing, not a nicety.
  Separately, Android 15+ blocks `BOOT_COMPLETED` receivers from starting `dataSync` foreground
  services — the boot receiver must only set `AlarmManager` alarms.

### Open questions for Hasan

- **Piper voice licence.** The `en_US-amy-medium` MODEL_CARD points its dataset at
  `MycroftAI/mimic3-voices` with the licence field given only as "See URL", and Mycroft AI is
  defunct. Needed for the README attribution section and AGPL compliance. Not blocking until P5.

### Deliberately not done

- `platforms;android-37` **does not exist** as an SDK package — only `android-37.0`, `.1`, `.2`
  (plus betas). Both workflows resolve the newest stable `37.x` at runtime instead of composing
  the package name from the major version. AGP maps `compileSdk = 37` onto the highest installed
  `37.x`.
- No `compileSdkMinor` in the catalog. It was there and unread, which is worse than absent.


---

## P3–P5 — the working assistant (2026-09-02)

### What exists

- 14 tools in `tools/MachineTools.kt`, dispatched through a table in `ToolExecutor`.
  Output is constrained by GBNF generated in `ToolGrammar`, so a reply is a valid call
  by construction. Integer arguments carry ranges compiled into the grammar.
- `TimeResolver` does every calculation the model is not asked to do. Heavily unit tested.
- `PromptDialect` owns prompt, grammar and parser together, because a fine-tuned model
  only behaves when all three match. Two implementations: JSON (Gemma) and FunctionGemma.
- `tts/PiperEngine` speaks replies. `models/ModelArchive` unpacks the voice's tarball.
- Live partial transcripts: the recorder emits a snapshot every 500 ms, the session
  transcribes it if the transcriber is free and drops it otherwise.

### Verified on device

- Side button opens a session; whisper, llama and piper all load (0.6 s / 2.2 s / 2.9 s).
- Partials converge: "Set a title." then "Set a timer for 10" then the full sentence.
- Every user-visible failure is logged, so a session that ended badly is distinguishable
  over adb from one that never ended.

### Things that cost a day and should not cost another one

- **`llama_batch_get_one` leaves `logits` null**, which llama reads as "logits for every
  token". With Gemma's 262144-wide vocabulary that reserved ~420 MB and ran the output
  projection once per prompt token. Mark only the last token.
- **Prefix reuse needs `swa_full`.** Gemma 3's sliding-window layers otherwise keep only
  the last 1024 tokens, and the reused prefix reads keys that are gone. The symptom is
  not a crash: replies stay syntactically valid and their contents collapse to a constant.
- **`n_batch` must cover the longest prompt.** llama asserts rather than splitting, and
  `ggml_abort` inside `llama_context::decode` is not catchable.
- **`n_ubatch` costs about a megabyte of compute buffer per step** at this vocabulary
  size. 512 asked for 518 MiB and the process was killed reserving it.
- **A ~620 token prompt makes Gemma 3 1B stop discriminating** and start reproducing the
  first worked example whatever it was asked. Keep it under ~450; `MachineToolsTest`
  guards this.
- **Do not ask a 1B model to do arithmetic.** It answered "ten minute timer" with 180,
  copied from the nearest example.
- **GBNF rule names may not contain `_`**, and llama.cpp reports a bad grammar with no
  rule and no offset. `GrammarBisectTest` bisects; `nativeGrammarAccepts` answers the
  different and more useful question of whether a grammar *admits* a given string.
- **Android's ICU regex rejects a bare `}`** that the JVM tolerates.
- **Git Bash rewrites `/sdcard/...` into a Windows path.** `adb push` reports success and
  the file lands nowhere useful. Set `MSYS_NO_PATHCONV=1` and give the source as `C:/...`.

### Needs Hasan's decision

Locked decision 7 says tasks are stored in Room. They are not: `ReminderStore` writes
them to the same markdown files the user can open and edit, which is what makes the
context files a second brain rather than a separate list the assistant keeps to itself.
It persists correctly and is scheduled with exact alarms as specified. Worth confirming
before it becomes expensive to change.

### The wake word was built and then removed

"Hey root" worked — sherpa-onnx, already pinned for the voice, contains a keyword spotter,
and the phrase spotted in 252 ms without firing on other speech. It was removed anyway, at
Hasan's call, because always-on listening costs a permanent notification, a microphone
indicator and real battery, and the platform gives a sideloaded app no cheaper option: the
low-power hotword APIs need a permission whose protection level is `internal|preinstalled`,
so the app would have to be part of the system image. Holding the assistant role does not
help.

If it is ever wanted back, the two things that cost time were: the keywords file is
whitespace-separated in every field including the display name, so `@HEY ROOT` makes the
parser look for a token called ROOT and refuse to start; and the phrase must be written as
word-pieces (`▁HE Y ▁RO O T`) because the shipped library has no tokeniser in it.

### The prompt cache was fine; the test suite was eating it

Worth writing down because the investigation went the wrong way for an hour. The symptom
was real: no `.prompt-cache` file beside the small model, and a log line reading
`prompt is 501 tokens, 0 reused` with a four-second prefill. The conclusion drawn from it
was wrong. The file was being deleted by the *engine* prompt-cache test, which writes a
deliberately corrupt cache and removes it in a `finally` — so every full `deviceTest` run
ended with no cache on disk, and the next command after a test run paid a cold prefill.

The suspected cause was the write being launched on the overlay's own scope, which is
cancelled when the panel closes. That scope really is cancelled, and the write really is
launched on it, but the write survives anyway: it is a single native call, and cancelling
the coroutine wrapped around it does not interrupt it once it has begun. Reinstating the
supposed bug and measuring showed the cache landing exactly as before.

Measured afterwards, on a cold process with a cache already on disk: 499 tokens restored,
466 of 497 reused, prefill 353 ms, whole command 803 ms. The cross-process cache has been
working the entire time.

What did change is a `join` before the model is freed. The save walks the very cells
`nativeFree` releases, and that race is real even though it is not the one being chased —
state saving has already caused one use-after-free crash in this project.

### Reminders that keep asking, and what it cost to find out how

A reminder that fires once can be missed by being in another room, so it now asks again
every five minutes until it is dealt with. The stopping condition is deliberately the user's
own list rather than a counter this code keeps: every repeat re-reads the task file first and
stops the moment the task is ticked off, deleted or moved. That is what keeps it from
becoming the re-arm loop this file has already produced once. It also gives up after eight
tries — something ignored eight times will not be answered by a ninth, and a notification
that can never be silenced is a worse failure than one that is missed. The notification is
ongoing so a swipe cannot dismiss it, and a repeat says "Still waiting" so it reads as the
same reminder asking again rather than a new one.

The interesting part was reaching any of it by voice.

**Eighteen tools is too many for this model, and it is measurable.** Adding list, complete
and reschedule took device routing from one failure to five, reproducibly. "Timer for ten
minutes" came back as `{hours=20, minutes=50, seconds=50}` — the wall clock, read straight
out of the prompt's own "Now:" line. The prompt measured 620 tokens, which is exactly the
figure PromptBuilder's comment names as where a 1B stops discriminating and starts copying
its first example. The 300-word ceiling in MachineToolsTest did not catch it because 297
words was already broken; it is now 280, below the cliff rather than on it.

Withdrawing reschedule took it from five failures back to one. Withdrawing the other two
took it to none.

**So all three are recognised before the model instead**, the way the timezone question is.
None of them needs one: the clock, the task list, and which task was named are things the
device knows exactly. The tools stay implemented and their handlers stay wired — only the
declarations the model sees are gone. `TaskQuestion` recognises the asking, `TaskMatcher`
resolves which task was meant, and both are unit-tested with the false positives that would
matter more than the misses: "remind me to mark the report done" contains every finishing
word there is and must still write something down.

The one that decided it: declared as a tool, "mark the bins done" resolved to
`create_reminder`. That does not fail — it files a second copy of the task and says it did.
A wrong answer that looks like a right one is the worst kind, and it is the reason this
family belongs outside the model's vocabulary rather than in it.

### What came back from the fork

ijazshare/assistant branched at 0400f45 and fixed several things independently, mostly by
measuring them on hardware. Its UI work is deliberately not taken; these are:

**The microphone was being fed digital zeros.** RECORD_AUDIO is granted "while in use", and
the assistant records from an overlay with no activity in the foreground, so the appop hands
it silence. Proven there the way I could not prove it here: the same capture reads real audio
with the app foregrounded and zeros with it backgrounded. My own tests always had the app
effectively foregrounded, which is exactly why the audit's silenced-stream finding looked
unreproducible. A microphone-typed foreground service, running only while recording, puts the
process into the state the appop checks for. This is the rest of the deafness.

**A stale prompt cache does not fail to load, it corrupts generation.** The prompt is baked
into the APK, so a cache written by a different build describes a different prompt — and
loading one made the router copy its first example and answer almost everything with "set an
alarm". That is the true explanation of something I had written off as model weakness: my own
"beep -> set_alarm at 22:00" appeared right after I changed tool descriptions, with a cache
from before the change still on disk. Caches are now stamped with the package's install time
and dropped when it moves.

**The user's name and context do not belong in a routing prompt.** Measured there: the default
name "Admin" is an ordinary word and dragged "text me" to set_alarm, cutting routing from 86%
to 57%; injecting the tasks file cut send_message from 6/8 to 4/8. Both still reach
AnswerPrompt, where they personalise an answer. Removing them also freed the prompt budget I
had been fighting over.

**And VOICE_RECOGNITION can open and then feed silence** after an audio-route change, so the
recorder now falls through to MIC and reports capture stats — peak amplitude and frame count —
so a silent source is distinguishable from an early dismissal in the log.

Also taken: ContactMatch (the contacts provider matched "me" to "MI Aziz" and a message went
to a father-in-law at 3am), MessageBody (a body whose words are mostly absent from the
transcript was invented, not dictated, and a text has no undo), and an AnswerPrompt that
declines live or unknowable figures instead of inventing them.

One change is a deliberate reversal of a safety decision and is flagged rather than hidden:
send_message now sends through SmsManager instead of composing in the messaging app. The
original comment argued that silently sending speech-recognised text is the one action with no
undo. MessageBody guards the worst case, but this is a posture change and it is Hasan's.

### The time elsewhere was never the model's job

"What time is it in Berlin" survived every wording change — read_screen, read_notifications,
read_screen again — because a 1B was being asked to decide it at all. The phone carries the
whole IANA zone table. LocalAnswers now reads the leftover words as a place and answers from
`ZoneId`, exactly as it already answered the plain clock question, and the model never sees
it. Exact instead of a coin toss, and it costs no tokens.

### Tool descriptions are a fixed budget, and I spent it badly

Read out of Hasan's own query log on the S26, not reasoned about. Three real faults.

**read_screen had become the drain every utterance fell down.** I had widened its
description to "or answer a question about them", and a 1B reads the tool list as a bag of
words, so the broadest invitation beat every narrower tool: "what time is it in Berlin",
"hello, hear me" and "beep" all resolved to read_screen. Narrowed back. Its optional
`question` parameter went too — given one, the model filled it with the parameter's own
description, verbatim: `{"question":"What was asked about the screen."}`. ScreenQuestion
already decides that from the transcript and needs nothing from the model.

**"Set a 30 minute timer" became a reminder at hour 30.** Two separate causes. The word
"timer" appeared nowhere in set_timer's description while "time" sat in create_reminder's,
so the match went to the wrong tool. And `FunctionGemmaDialect` compiled every integer as a
bare unbounded `integer`, ignoring `param.range`, where the JSON dialect compiles the real
bounds — which is how an hour of 30 was representable at all. Both fixed; the range compiler
is now shared rather than reimplemented per dialect.

**And then I made it worse before I made it better.** Rewriting five descriptions to be more
explicit pushed the prompt past the 300-word ceiling that `MachineToolsTest` exists to
defend, and on-device routing degraded badly: `set_timer {hours=3, minutes=30, label=10}}`.
That test is not a style rule. Prompt length is a resource, tool descriptions all draw on
the same budget, and spending it on one tool takes it from another.

What is left is not tunable by wording: "beep" still becomes an alarm, because the grammar
forces exactly one tool per turn and a 1B scoring 16.64 on BFCL has no reliable way to
decline. That is a model-capability problem and it belongs to the model survey's first
recommendation, not to another round of adjectives.

### The crash loop was R8, and it was never device-specific

v0.1.0 crash-looped on the S26 Ultra and ran on the Z Flip, so three investigations went
looking for what was different about the phone. Nothing was. The device had twelve identical
native crashes waiting in dropbox, all one line:

    JNI DETECTED ERROR IN APPLICATION: fid == null
      in call to GetObjectField
      from com.k2fsa.sherpa.onnx.OfflineTts.newFromFile(OfflineTtsConfig)

sherpa-onnx's C++ reads its config objects field by field with `GetFieldID`. `OfflineTtsConfig`
is a plain Kotlin class with no native methods of its own, so the keep rule that protects
`native <methods>` did not cover it, R8 renamed the fields, `GetFieldID` returned null, and
the process aborted the instant the voice loaded. Every release build did this. Only release,
because debug is not minified.

It looked device-specific for a dull reason: the release variant had only ever been *launched*
on the development phone, never driven through a voice session. Launching it exercises none of
this. The fix is two keep rules; the lesson is that the release variant needs the same
end-to-end test the debug one gets, on both handsets.

### Speech was never the 1.6 seconds it was billed for

With synthesis and playback timed separately, on both phones:

    piper: synthesis 146 ms, playback start 3175 ms, 3.16 s of audio, 6 threads

Synthesis is 111-150 ms. `play()` blocks until the audio finishes, so the "1.6 s of TTS" in
the latency budget was measuring the length of the spoken sentence, not work. Time to first
word is synthesis plus AudioTrack start. Every model swap considered for the voice was
therefore aimed at a cost that does not exist — worth knowing before spending 62 MB on a
better vocoder.

`PiperEngine` was also still pinned at two threads while Whisper and Llama both scaled to the
core count, contrary to CLAUDE.md's own runtime policy. It now runs at six.

### A build that cannot say why it died

v0.1.0 was tagged, signed, published, and crash-looped on Hasan's S26 Ultra — the phone
CLAUDE.md names as the primary target and the one phone that is not reachable over adb. The
Z Flip everything is developed against ran the same signed APK fine.

Three hypotheses were checked and all three were wrong, which is the useful part:

- **16 KB memory pages.** New devices ship with them and unaligned native libraries fail to
  load. All twenty-five `.so` files in the published APK are aligned to `0x4000`.
- **WorkManager's foreground service type.** A fresh install downloads models immediately,
  and `SystemForegroundService` is declared by WorkManager with no `foregroundServiceType`,
  which throws when a worker asks for `dataSync`. There are crash records for exactly this
  on the test phone — but they are historical, and `aapt2 dump xmltree` on the shipped APK
  shows `foregroundServiceType=0x1` present. Already fixed, already shipped.
- **The release variant itself.** R8, resource shrinking, no `.debug` suffix, no models. The
  published APK was downloaded, installed, cleared to a fresh state and launched on the Flip.
  It runs.

So the cause is still unknown, and that is the point: there was no way to find out. logcat
needs a cable and a laptop, and the app kept no record of its own death. A crash that only
happens on the device you cannot attach to is undiagnosable, and no amount of reasoning
about it is worth one stack trace.

`CrashLog` now installs an uncaught-exception handler that writes the trace, device, Android
version and ABI to `files/crash/`, keeps the last ten, and shows them at the top of the
History screen as selectable text. Nothing is uploaded and there is no SDK — the privacy
invariants are about telemetry leaving the device, not about the owner being able to read
their own crash. Native SIGSEGVs still need a tombstone; this catches the Kotlin side.

Verified by crashing the app on purpose with `am crash` and reading the file back.

### The wrong answers were structural, not the model being stupid

Read from Hasan's own query log rather than reasoned about. Three faults, and none of them
was the model choosing badly from a fair set of options.

"Take a screenshot" became `read_screen`, which recited a Discord conversation at him. There
was no screenshot tool. The grammar emits one alternative per tool, so the sampler was
physically incapable of producing anything else — the wrong answer was guaranteed before the
model ran. There is now a `take_screenshot`, saving through MediaStore into DCIM/Screenshots
so the file appears in the gallery; app-private external storage is not indexed and would
have saved a picture nobody could find.

"Read the screen and summarise it" spoke raw OCR because `read_screen` declared no
parameters, so the model's entire legal output was `{"tool":"read_screen"}` and every word
past "screen" was destroyed at the grammar boundary. It now carries an optional question and
escalates to the 4B — but the *decision* to escalate is taken from the transcript, not from
whether the 1B filled the argument, because on device it filled it about half the time. A 1B
should decide the tool; it should not decide whether a feature works.

The two tools collide on the token "screen", which is in both names, so "screen" cannot be
what separates them. Their descriptions were written with disjoint vocabularies —
speak/words/read only in one, picture/gallery/screenshot/save only in the other — and that
alone separates them on device, with no worked example. Adding examples was tried first and
made it worse: two extra lines pushed reminders into `set_alarm`, exactly the failure
PromptBuilder already warns about.

A learned phrase is only the best answer available at the time, and the cache is consulted
before the model is even loaded — so "take a screenshot" would have gone on running
read_screen forever, no matter what shipped. The cache now carries a fingerprint of the tool
names *and* descriptions and forgets everything when they change.

Not verified end to end: the saved screenshot. Reinstalling revokes the accessibility grant
for a sideloaded app, and Android will not re-bind the service until "Allow restricted
settings" is tapped on the phone, which adb cannot do. Tool routing is covered by tests on
the device; the file write is not.

### The deafness had three causes, and the battery was not one of them

Tested first, because it was the proposed fix. Forced the phone into verified deep doze
(`dumpsys deviceidle get deep` reports IDLE — note `get state` is not an option on this
build and silently reports nothing, which made the first run of the experiment worthless),
held it there two minutes, and summoned. With the exemption and without it the process
survived and the deaf window was 77 ms versus 103 ms: the same number twice. Holding the
assistant role already keeps this process bound, so the exemption had nothing left to buy.
It was not added as a required permission.

What was actually wrong, found by measuring the window between the overlay appearing and
`AudioRecord.startRecording()` returning:

1. **The noise floor was calibrated on the user's voice.** The ten frames after the lead-in
   set "the room", and push-to-talk invites speaking immediately. Averaged, a voice in that
   window becomes the floor, the threshold lands three times above anything a person
   produces, and every later frame is silence. Five seconds on it announces that it did not
   hear anything and discards a perfectly good recording. Now the quietest frame is taken
   rather than the average, the floor may fall but never rise, and a hard ceiling means a
   measurement above room tone can never be believed as room tone.
2. **The settling window was 1700 ms.** Sized for a greeting spoken aloud, except nothing is
   spoken before listening — the greeting is drawn, and its per-word ticks are already muted
   while the microphone is open. All it bought was 1.7 seconds in which speech could not be
   detected, which made any command shorter than the window inaudible by construction. Now
   300 ms, which is the microphone's own opening transient and nothing more.
3. **The microphone was opened on top of one still closing.** `stopListening()` cancelled the
   capture job and then discarded the reference, so the next summon had nothing to wait for
   and asked the audio system for a second recorder while the first was still releasing:
   two and a half seconds of an overlay that is up, looks ready and hears nothing. Keeping
   the reference exposed the deeper fault — the capture job also ran transcription, the
   language model and the spoken reply inside the flow collector, so joining it waited for
   the whole previous command. Nine seconds, once. Capture and processing are now separate
   jobs; the capture job's only duty is the microphone.

Measured across cold start, repeated summons, rapid succession and after screen-off idle:
41-373 ms, median about 90. Before: anywhere from 45 ms to 9694 ms.

### The give-up clock and the lead-in

The endpointer ignores a lead-in while the overlay announces itself, because the greeting
is audible to the microphone. The give-up clock has to start *after* that lead-in, not at
the same moment: charging the announcement to the user left about a second to begin
speaking, and every summon after the first came back "I did not hear anything" three
seconds later without a word being transcribed. It reads as the assistant working once and
then going deaf.

### Learned phrases are shapes, not sentences

What is remembered is the phrase with its numbers taken out. "Set a three minute timer"
teaches the shape `# minutes timer`, and "set a 25 minute timer" then runs instantly with
25 in it. One entry covers every length rather than one entry per length, and the numbers
are checked against the same bounds the grammar enforces, so a shape learned from a timer
cannot produce an hour of forty.

Each number in the call records which spoken number it came from and what was added to it
— only ever twelve, for an afternoon hour, so a shape learned from "6pm meaning 18" gives
20 for "8pm" rather than 18 again.

A shape learned wrongly can be forgotten on its own from the History screen. That matters:
the assistant answered "what time is it" by reading out the notification shade, and then
learned it, so it kept doing it. The clock is now answered before the model is consulted
at all — the phone knows the time exactly and a language model only knows it because it
was written into a prompt.

### One model in memory at a time

Loading the 4B beside the 1B fits on paper — about 5 GB on an 11 GB phone — and does not
fit in practice. Measured with both resident: 200 MB free, 1.2 GB of the app swapped, and
every command afterwards decoding at under one token per second. The small model is
unloaded before a question goes to the large one, and the large one is freed as soon as
it has answered. Both keep a prompt cache on disk, so the reload is a second or two.

  |  | both resident | one at a time |
  |---|---|---|
  | free memory | 200 MB | 745 MB |
  | app swapped | 1.2 GB | 287 MB |
  | command after a question | 21-31 s | 0 ms from cache |
  | question | 39 s | 9.8 s |

The reference device is also thermally throttled to status 4 after a long session of this
work, so every number here is a floor rather than the phone at its best.

### The adversarial review (2026-09-02)

Four readers over the new code, every finding handed to a separate reader whose job was
to refute it: 34 candidates, 29 survived, the dangerous ones fixed. Worth repeating on
anything substantial — most of these were invisible to the test suite because the tests
were written by the same reasoning that wrote the code.

The one that mattered most: a reminder that fired while the app was dead re-armed itself,
because the alarm started the process and the process rescheduled everything overdue. An
evening reminder became a notification every five seconds until reboot. Fixed by dropping
the due field on delivery.

Patterns worth remembering, all of which bit here:

- **Two objects over one file.** The screen and the assistant each held a cache; clearing
  one had no effect on the other, which then wrote its stale copy back.
- **A default that cannot be told from a reading.** A minute of zero meant both "none were
  said" and "the model dropped them", so a wrong alarm time was cached as correct.
- **A guard applied too widely.** The relative-time veto used the word "this", so
  "read this" could never be learned.
- **Raw strings and escapes.** The answer marker held a literal backslash and matched
  nothing; the enum literal earlier needed a trailing quote a raw string cannot carry.
- **Work on the main thread inside the latency budget.** Cache reads, history writes and
  engine teardown all sat in front of the reply.

### Not done
- Wake word "hey root" — deferred by Hasan.
- Signed release build has never been exercised.
