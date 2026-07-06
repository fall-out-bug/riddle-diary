# Riddle Diary — Tracer Bullet Implementation Plan (Phase 1), v3

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Android toolchain on the WSL2 dev box, scaffold a Jetpack Compose project, and ship a tracer bullet that runs on the BOOX Note Air5 C: the user writes a word with the stylus, taps send, and a canned response draws itself on the page glyph-by-glyph as handwriting strokes.

**Architecture:** Single-Activity Compose app, one full-screen `Canvas` (`DrawSurface`) that captures EMR stylus input and renders both the user's ink and a self-animating canned response. No ML Kit, no LLM, no Room in this phase — the response is hardcoded text whose glyphs come from `Paint.getTextPath()` and animate via `android.graphics.PathMeasure`. This isolates the riskiest unknown (does the "ink appears" animation feel right on color e-ink?) before building the full pipeline. **Path-type decision (fixed):** response glyphs are `android.graphics.Path` end-to-end (`getTextPath`/`PathMeasure`/`getSegment` are android APIs), rendered via `DrawScope.drawIntoCanvas { it.nativeCanvas.drawPath(...) }` with an `android.graphics.Paint`. User ink strokes are built as a Compose `androidx.compose.ui.graphics.Path` from offsets and drawn with `DrawScope.drawPath`. The two are clearly distinguished; there is exactly one decision.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2, Jetpack Compose (BOM 2024.12.01), JDK 21, Android SDK 35 / build-tools 35.0.0 / platform-tools (adb), minSdk 31 / targetSdk 35. Dev box = **WSL2 (Linux)**; deploy via `usbipd-win` (or Windows-side adb).

## Global Constraints

- Device: BOOX Note Air5 C, **Android 15 (API 35)**, EMR stylus.
- `minSdk = 31`, `targetSdk = 35`, `compileSdk = 35`.
- Stylus only produces ink: filter on `PointerInputChange.type == PointerType.Stylus`. Finger/eraser/barrel ignored in this phase (full screen is one Canvas; no scroll container yet).
- Build with **JDK 21** (the system has JDK 26, which AGP 8.7.x does not officially support). Point Gradle at JDK 21 via `org.gradle.java.home`.
- All Gradle config in **Kotlin DSL**.
- No comments in code unless requested.
- **Animation cadence respects e-ink** (spec §4.4): redraw tick is a named constant `ANIMATION_TICK_MS`, not a blind 60 fps loop; Task 6 tunes it on-device.
- One commit per task; commit messages `type: subject`.

---

## File Structure

```
scribble-ai/
  docs/superpowers/plans/2026-07-05-riddle-diary-tracer-bullet.md   # this file
  settings.gradle.kts
  build.gradle.kts            # root
  gradle.properties
  gradle/wrapper/*            # generated
  gradlew                     # generated
  local.properties            # sdk.dir (gitignored)
  .gitignore
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/com/scribble/riddle/
      MainActivity.kt
      ui/DrawSurface.kt         # Canvas: stylus capture + render user ink + render response
      ui/HandwritingRenderer.kt # DrawScope.renderAnimated(androidPaths, progress, ink) + glyphLocal()
      ui/InkPathFactory.kt      # String -> List<android.graphics.Path> via Paint.getTextPath
      ui/StrokeStore.kt         # tracer-only state holder (object; replaced later)
    src/main/res/values/themes.xml
    src/main/res/values/strings.xml
    src/main/res/font/caveat.ttf         # OFL Cyrillic handwriting font (outline font; see note)
    src/main/res/raw/caveat_ofl.txt      # OFL license text
    src/test/java/com/scribble/riddle/ui/GlyphLocalTest.kt
```

Responsibilities:
- `DrawSurface.kt` — single canvas owner; captures stylus events into `Stroke`s; renders user ink (Compose Path) and the response (delegates to `HandwritingRenderer`).
- `HandwritingRenderer.kt` — `DrawScope.renderAnimated(paths: List<android.graphics.Path>, progress, ink)`; segments each path with `PathMeasure.getSegment` (multi-contour via `nextContour`) and draws via `nativeCanvas.drawPath`. Also exposes pure `glyphLocal(progress, index, total)` for unit testing.
- `InkPathFactory.kt` — `Paint`-backed; `pathsFor(text, x, y): List<android.graphics.Path>`, one path per char.
- `StrokeStore.kt` — `object` holding `mutableStateListOf<android.graphics.Path>` + `mutableStateOf<Float>` (tracer only).

---

## Task 0: Environment setup (JDK 21, Android SDK, adb) — Linux/WSL2

**Files:**
- Create: `local.properties` (gitignored), `.gitignore`

**Interfaces:** Produces working `adb`, `sdkmanager`, JDK 21; `ANDROID_HOME` set.

- [ ] **Step 1: Install JDK 21 (Linux formula, NOT a cask)**

`brew install --cask` is macOS-only. On Linuxbrew use the formula:
```bash
brew install openjdk@21
```
**Detect** the JDK home (do not hard-code it — the `libexec` layout differs across Homebrew versions; there is no `/usr/libexec/java_home` on Linux):
```bash
OPT="$(brew --prefix openjdk@21)"
JDK21_HOME="$OPT/libexec"
[ -x "$JDK21_HOME/bin/java" ] || JDK21_HOME="$OPT/libexec/openjdk.jdk/Contents/Home"
test -x "$JDK21_HOME/bin/java" && echo "JDK21_HOME=$JDK21_HOME"
```
Expected: `JDK21_HOME=...` printed with a path whose `bin/java` exists. Remember this path for Task 1 Step 4 (`org.gradle.java.home`).

- [ ] **Step 2: Install Android command-line tools (direct download — most reliable on Linux)**

```bash
mkdir -p "$HOME/android-sdk/cmdline-tools"
cd /tmp
curl -L -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q cmdline-tools.zip
mv cmdline-tools "$HOME/android-sdk/cmdline-tools/latest"
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```
> Pin the URL to a specific `commandlinetools-linux-<build>_latest.zip` from https://developer.android.com/studio#command-line-tools-only if the one above 404s. Add the two `export` lines to `~/.bashrc` so they persist.

Verify:
```bash
sdkmanager --version
```
Expected: a version line.

- [ ] **Step 3: Accept licenses (non-interactive) + install platform 35, build-tools, platform-tools**

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```
Verify:
```bash
adb --version
```
Expected: a version line.

- [ ] **Step 4: Record `local.properties` (gitignored) and `.gitignore`**

`local.properties` (replace the JDK path with Step 1's output):
```
sdk.dir=/home/<user>/android-sdk
```
`.gitignore`:
```
.gradle/
build/
local.properties
*.iml
.idea/
captures/
.cxx/
app/build/
```
Commit only `.gitignore`:
```bash
git add .gitignore
git commit -m "chore: gitignore for android project"
```

---

## Task 1: Project scaffold (builds a debug APK)

**Files:** `settings.gradle.kts`, `build.gradle.kts` (root), `gradle.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/java/com/scribble/riddle/MainActivity.kt`; generated `gradlew`, `gradle/wrapper/*`.

**Interfaces:** Produces a buildable Compose app.

- [ ] **Step 1: Bootstrap the Gradle wrapper pinned to 8.10.2**

```bash
brew install gradle
JAVA_HOME="$JDK21_HOME" gradle wrapper --gradle-version 8.10.2
```
> `JAVA_HOME` points Gradle at JDK 21 (the system default is JDK 26, which brew's Gradle may refuse). `brew install gradle` installs the latest Gradle only to run `wrapper` once; the wrapper itself is pinned to 8.10.2. All later steps use `./gradlew` (which reads `org.gradle.java.home` from `gradle.properties`).

- [ ] **Step 2: `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "riddle-diary"
include(":app")
```

- [ ] **Step 3: root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

- [ ] **Step 4: `gradle.properties`**

Set `org.gradle.java.home` to the **detected** JDK 21 home from Task 0 Step 1 (the path whose `bin/java` you verified). Example (Linuxbrew `libexec` layout):
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
org.gradle.java.home=/home/linuxbrew/.linuxbrew/opt/openjdk@21/libexec
```
> Use YOUR verified `JDK21_HOME` value; if your layout is the bundle form, it ends in `.../libexec/openjdk.jdk/Contents/Home`. Verify Gradle can launch it in Step 8 (build will fail clearly if the path is wrong).

- [ ] **Step 5: `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.scribble.riddle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scribble.riddle"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 6: `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.RiddleDiary"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 7: resources + empty `MainActivity`**

`res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.RiddleDiary" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```
`res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Riddle Diary</string>
</resources>
```
`MainActivity.kt`:
```kotlin
package com.scribble.riddle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            }
        }
    }
}
```

- [ ] **Step 8: Build the debug APK**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "scaffold: gradle compose project building a debug apk"
```

---

## Task 2: DrawSurface — stylus ink capture

**Files:** Create `app/src/main/java/com/scribble/riddle/ui/DrawSurface.kt`; modify `MainActivity.kt`.

**Interfaces:**
- Produces: `@Composable fun DrawSurface(modifier: Modifier, response: List<android.graphics.Path>, responseProgress: Float, responseInk: Color)`.

- [ ] **Step 1: `DrawSurface.kt`**

```kotlin
package com.scribble.riddle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

internal data class Stroke(val points: List<Offset>)

@Composable
fun DrawSurface(
    modifier: Modifier = Modifier,
    response: List<android.graphics.Path> = emptyList(),
    responseProgress: Float = 0f,
    responseInk: Color = Color(0xFF5A4A2F),
) {
    val strokes = remember { androidx.compose.runtime.mutableStateListOf<Stroke>() }
    var current by remember { mutableStateOf<MutableList<Offset>?>(null) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    event.changes.forEach { change ->
                        if (change.type != PointerType.Stylus) return@forEach
                        when {
                            change.changedToDown() -> current = mutableListOf(change.position)
                            change.pressed -> current?.add(change.position)
                            change.changedToUp() -> {
                                current?.add(change.position)
                                current?.let { strokes.add(Stroke(it.toList())) }
                                current = null
                            }
                        }
                        change.consume()
                    }
                }
            }
        }
    ) {
        val userInk = Color(0xFF1A1A2E)
        if (response.isNotEmpty()) {
            renderAnimated(response, responseProgress, responseInk)
        }
        val all = strokes + (current?.let { listOf(Stroke(it)) } ?: emptyList())
        all.forEach { stroke ->
            if (stroke.points.size >= 2) {
                val p = Path()
                p.moveTo(stroke.points[0].x, stroke.points[0].y)
                for (i in 1 until stroke.points.size) p.lineTo(stroke.points[i].x, stroke.points[i].y)
                drawPath(
                    p,
                    color = userInk,
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
```

> The `Up` point is appended before commit so short taps still produce a 2-point stroke. `changedToDown`/`changedToUp`/`pressed` and `change.type`/`change.position` are the Compose `PointerInputChange` API (Compose 1.7 / BOM 2024.12.01). If on the BOOX the EMR stylus does **not** surface as `PointerType.Stylus`, fall back to `pointerInteropFilter { e -> if (e.toolType == android.view.MotionEvent.TOOL_TYPE_STYLUS) { … } else true }` — record which path worked as a Task-6 observation.

- [ ] **Step 2: Host in `MainActivity`**

Replace the empty `Surface` content with:
```kotlin
DrawSurface(modifier = Modifier.fillMaxSize())
```
(`DrawSurface` has defaults for the response params.)

- [ ] **Step 3: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: DrawSurface captures stylus ink"
```

---

## Task 3: HandwritingRenderer — real animated glyph drawing

**Files:** Create `app/src/main/java/com/scribble/riddle/ui/HandwritingRenderer.kt`, `app/src/test/java/com/scribble/riddle/ui/GlyphLocalTest.kt`.

**Interfaces:**
- Produces: `fun DrawScope.renderAnimated(paths: List<android.graphics.Path>, progress: Float, inkColor: Color)` and `internal fun glyphLocal(progress: Float, index: Int, total: Int): Float`.

- [ ] **Step 1: `HandwritingRenderer.kt` (real implementation)**

```kotlin
package com.scribble.riddle.ui

import android.graphics.PathMeasure
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas

internal fun glyphLocal(progress: Float, index: Int, total: Int): Float {
    val perGlyph = 1f / total
    return ((progress - index * perGlyph) / perGlyph).coerceIn(0f, 1f)
}

fun DrawScope.renderAnimated(
    paths: List<android.graphics.Path>,
    progress: Float,
    inkColor: Color,
) {
    if (paths.isEmpty()) return
    val total = paths.size
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = 3f
        color = inkColor.toArgb()
    }
    drawIntoCanvas { canvas ->
        paths.forEachIndexed { index, fullPath ->
            val local = glyphLocal(progress, index, total)
            if (local <= 0f) return@forEachIndexed
            val pm = PathMeasure(fullPath, false)
            val dst = android.graphics.Path()
            pm.getSegment(0f, pm.length * local, dst, true)
            while (pm.nextContour()) {
                val extra = android.graphics.Path()
                pm.getSegment(0f, pm.length * local, extra, true)
                dst.addPath(extra)
            }
            canvas.nativeCanvas.drawPath(dst, paint)
        }
    }
}
```

> `drawIntoCanvas` and `DrawScope.nativeCanvas` are real (`androidx.compose.ui.graphics.nativeCanvas`, `androidx.compose.ui.graphics.drawscope.drawIntoCanvas`). `Color.toArgb()` is `androidx.compose.ui.graphics.toArgb()`. `PathMeasure`/`getSegment`/`nextContour` are `android.graphics.*`.

- [ ] **Step 2: `GlyphLocalTest.kt` (pure unit test)**

```kotlin
package com.scribble.riddle.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlyphLocalTest {
    @Test
    fun first_glyph_at_start_is_zero() {
        assertEquals(0f, glyphLocal(0f, 0, 4), 0f)
    }

    @Test
    fun third_glyph_halfway_at_progress_0_625() {
        assertEquals(0.5f, glyphLocal(0.625f, 2, 4), 0.0001f)
    }

    @Test
    fun clamps_before_glyph_window() {
        assertEquals(0f, glyphLocal(0.1f, 3, 4), 0f)
    }

    @Test
    fun full_progress_completes_last_glyph() {
        assertEquals(1f, glyphLocal(1f, 3, 4), 0f)
    }
}
```

- [ ] **Step 3: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.scribble.riddle.ui.GlyphLocalTest"
```
Expected: 4 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: HandwritingRenderer animates glyph paths via PathMeasure"
```

---

## Task 4: InkPathFactory — String -> glyph paths

**Files:** Create `app/src/main/res/font/caveat.ttf`, `app/src/main/res/raw/caveat_ofl.txt`, `app/src/main/java/com/scribble/riddle/ui/InkPathFactory.kt`.

**Interfaces:**
- Produces: `class InkPathFactory(paint: android.graphics.Paint)` with `fun pathsFor(text: String, originX: Float, originY: Float): List<android.graphics.Path>`.

- [ ] **Step 1: Bundle the OFL font + license**

```bash
mkdir -p app/src/main/res/font app/src/main/res/raw
curl -L -o app/src/main/res/font/caveat.ttf \
  "https://github.com/google/fonts/raw/main/ofl/caveat/Caveat%5Bwght%5D.ttf"
curl -L -o app/src/main/res/raw/caveat_ofl.txt \
  "https://raw.githubusercontent.com/google/fonts/main/ofl/caveat/OFL.txt"
```
> Caveat is OFL with Cyrillic coverage. **Important scope note (spec §8.1):** Caveat is a standard **outline** font, so `getTextPath()` yields closed contours and the animation will trace glyph *outlines*, not true single-stroke pen writing. That is acceptable for this tracer — its job is to prove the **rendering pipeline + e-ink cadence** end-to-end and to learn on-device how outline-tracing feels; the real single-stroke-font hunt (or generated strokes) is Phase 2.

- [ ] **Step 2: `InkPathFactory.kt`**

```kotlin
package com.scribble.riddle.ui

class InkPathFactory(private val paint: android.graphics.Paint) {
    fun pathsFor(text: String, originX: Float, originY: Float): List<android.graphics.Path> {
        val result = mutableListOf<android.graphics.Path>()
        val widths = FloatArray(1)
        var x = originX
        text.forEach { ch ->
            val p = android.graphics.Path()
            paint.getTextPath(ch.toString(), 0, 1, x, originY, p)
            result.add(p)
            paint.getTextWidths(charArrayOf(ch), 0, 1, widths)
            x += widths[0]
        }
        return result
    }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: InkPathFactory converts text to glyph paths (OFL Caveat)"
```

---

## Task 5: Tracer wiring — send button → canned response animates at e-ink cadence

**Files:** Create `app/src/main/java/com/scribble/riddle/ui/StrokeStore.kt`; modify `MainActivity.kt`.

**Interfaces:**
- Produces: a **Send** button; on tap, the canned Russian string is converted to glyph paths and animated 0→1 over `paths.size * PER_GLYPH_MS`, redrawing every `ANIMATION_TICK_MS` (e-ink cadence), drawn as sepia ink below the user's strokes.

- [ ] **Step 1: `StrokeStore.kt` (tracer-only state)**

```kotlin
package com.scribble.riddle.ui

import androidx.compose.runtime.mutableStateOf

object StrokeStore {
    val responsePaths: MutableList<android.graphics.Path> = androidx.compose.runtime.mutableStateListOf()
    val responseProgress = mutableStateOf(0f)
}
```
> `object` singleton with Compose state is a tracer-only shortcut; Phase 2 replaces it with `PageStore`/`SendOrchestrator`. Reads inside a `@Composable` are snapshot-tracked, so the wiring recomposes correctly.

- [ ] **Step 2: `MainActivity.kt` — button + e-ink-cadence animation driver**

```kotlin
package com.scribble.riddle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.core.content.res.ResourcesCompat
import com.scribble.riddle.ui.DrawSurface
import com.scribble.riddle.ui.InkPathFactory
import com.scribble.riddle.ui.StrokeStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                TracerScreen()
            }
        }
    }
}

private const val PER_GLYPH_MS = 350L
private const val ANIMATION_TICK_MS = 120L

@androidx.compose.runtime.Composable
private fun TracerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by StrokeStore.responseProgress
    var animJob by remember { mutableStateOf<Job?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            DrawSurface(
                modifier = Modifier.fillMaxSize(),
                response = StrokeStore.responsePaths,
                responseProgress = progress,
                responseInk = Color(0xFF5A4A2F),
            )
        }
        Button(
            onClick = {
                val typeface = ResourcesCompat.getFont(context, R.font.caveat)!!
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 64f
                    this.typeface = typeface
                }
                val factory = InkPathFactory(paint)
                StrokeStore.responsePaths.clear()
                StrokeStore.responsePaths.addAll(
                    factory.pathsFor("Я дневник. Пиши мне.", originX = 64f, originY = 500f)
                )
                val n = StrokeStore.responsePaths.size.coerceAtLeast(1)
                val totalMs = n * PER_GLYPH_MS
                animJob?.cancel()
                StrokeStore.responseProgress.value = 0f
                animJob = scope.launch {
                    val start = android.os.SystemClock.elapsedRealtime()
                    while (true) {
                        val t = (android.os.SystemClock.elapsedRealtime() - start).toFloat() / totalMs
                        StrokeStore.responseProgress.value = t.coerceIn(0f, 1f)
                        if (t >= 1f) break
                        delay(ANIMATION_TICK_MS)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("Отправить") }
    }
}
```

> `ANIMATION_TICK_MS = 120L` (≈8 Hz) is the e-ink cadence baseline (spec §4.4) — fast enough to read as progressive writing, slow enough that the Kaleido 3 panel keeps up without thrashing. **Task 6 tunes it on-device.** `PER_GLYPH_MS = 350L` makes each glyph take ~0.35 s.

- [ ] **Step 3: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: tracer bullet — send button animates canned handwriting at e-ink cadence"
```

---

## Task 6: Deploy to BOOX via USB + manual verification (the milestone)

**Files:** Create `docs/superpowers/notes/2026-07-05-tracer-observations.md`.

**Interfaces:** Produces a running app on the BOOX Note Air5 C.

- [ ] **Step 1: Forward the BOOX USB device into WSL2**

The dev box is WSL2, so USB devices are owned by Windows. Use `usbipd-win`:
1. Windows: `winget install usbipd-win`.
2. BOOX: Settings → Developer options → enable **USB debugging**; connect USB.
3. Windows PowerShell (admin): `usbipd list` → find the BOOX ADB interface BUSID.
4. `usbipd bind --busid <BUSID>`; `usbipd attach --wsl --busid <BUSID>`.
5. WSL2: `adb devices` → should list the BOOX.

> If `adb devices` shows `no permissions`, run `sudo adb kill-server && sudo adb start-server` or add a udev rule. **Alternative if usbipd is impractical:** install platform-tools on the **Windows** side and run `adb.exe install -r "\\wsl$\<distro>\home\<user>\projects\scribble-ai\app\build\outputs\apk\debug\app-debug.apk"` directly from Windows (no usbipd needed). The first connect shows an **Allow USB debugging** prompt on the BOOX — tap Allow.

- [ ] **Step 2: Install + launch**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.scribble.riddle/.MainActivity
```
Expected: app launches full-screen on the BOOX.

- [ ] **Step 3: Manual verification (human checkpoint — the whole point of the tracer)**

Verify on the device:
1. Stylus writing lays down dark ink; finger touch does NOT draw. (If neither works, see the `pointerInteropFilter` fallback note in Task 2.)
2. **Send** makes the canned Russian response draw itself glyph-by-glyph below the strokes.
3. The animation reads as "ink appearing"; note ghosting/stepping.
4. **Tune `ANIMATION_TICK_MS`** (Task 5) up/down and re-deploy until the feel is best — record the winning value.
5. Confirm whether the outline-tracing of Caveat reads acceptably as "handwriting" (this decides whether Phase 2 must find a true single-stroke font urgently).

- [ ] **Step 4: Record observations + commit**

Write `docs/superpowers/notes/2026-07-05-tracer-observations.md` with: winning `ANIMATION_TICK_MS`, stroke weight, ghosting level, outline-vs-pen feel, any stylus-API surprise. Then:
```bash
git add -A
git commit -m "docs: tracer bullet device observations"
```

---

## Self-Review (by plan author, v2)

- **Spec coverage:** Phase 1 only (toolchain + tracer bullet), matching spec §11. Full modules (ML Kit OCR, real LLM streaming, Room, SendOrchestrator, PauseDetector, PageLayout, single-stroke font) are deferred to later plans.
- **Placeholder scan:** No `TBD/TODO`. The two deferred *decisions* are stated explicitly: (a) Caveat is an outline font used only to validate the pipeline (Phase 2 finds a true stroke font), (b) `ANIMATION_TICK_MS` is tuned on-device in Task 6. All code blocks are complete and use real APIs.
- **Type consistency:** `InkPathFactory.pathsFor(...) -> List<android.graphics.Path>`; `StrokeStore.responsePaths: MutableList<android.graphics.Path>`; `DrawSurface(response: List<android.graphics.Path>, ...)`; `renderAnimated(paths: List<android.graphics.Path>, ...)`. User ink uses Compose `androidx.compose.ui.graphics.Path` (built from offsets). One decision, consistently applied.
- **API correctness verified:** `Paint.ANTI_ALIAS_FLAG`, `Paint.getTextPath(String,int,int,float,float,Path)`, `Paint.getTextWidths(char[],int,int,float[])`, `PathMeasure(path,bool)`, `.length`, `.getSegment(float,float,Path,boolean)`, `.nextContour()`, `DrawScope.drawIntoCanvas`, `DrawScope.nativeCanvas`, `Color.toArgb()`, `PointerInputChange.type/position/pressed/changedToDown/changedToUp`, `PointerType.Stylus`, `LocalContext.current`, `ResourcesCompat.getFont(context, R.font.caveat)`.
- **Build/toolchain verified for Linux/WSL2:** `brew install openjdk@21` (formula, not cask), Android cmdline-tools direct download, `yes | sdkmanager --licenses`, `brew --prefix openjdk@21` for the JDK home (no macOS `java_home`).

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-07-05-riddle-diary-tracer-bullet.md`. Per the user's directive ("реализуешь"), inline execution (superpowers:executing-plans) follows once this plan passes agent review (LGTM). Subagent-driven execution is avoided because dispatched subagents inherit the host's Session Protocol directive and previously stalled on Session-Card ceremony.
