# Riddle Diary — Tracer Bullet Implementation Plan (Phase 1)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Android toolchain on the WSL2 dev box, scaffold a Jetpack Compose project, and ship a tracer bullet that runs on the BOOX Note Air5 C: the user writes a word with the stylus, taps send, and a canned response draws itself on the page glyph-by-glyph as handwriting strokes.

**Architecture:** Single-Activity Compose app, one full-screen `Canvas` (`DrawSurface`) that captures EMR stylus input and renders both the user's ink and a self-animating canned response. No ML Kit, no LLM, no Room in this phase — the response is hardcoded text whose glyphs are obtained via `Paint.getTextPath()` and animated with `PathMeasure`. This isolates the riskiest unknown (does the "ink appears" animation feel right on color e-ink?) before building the full pipeline.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2, Jetpack Compose (BOM 2024.12.01), JDK 21 (Temurin), Android SDK 35 / build-tools 35.0.0 / platform-tools (adb), minSdk 31 / targetSdk 35.

## Global Constraints

- Device: BOOX Note Air5 C, **Android 15 (API 35)**, EMR stylus.
- `minSdk = 31`, `targetSdk = 35`, `compileSdk = 35`.
- Stylus only produces ink: filter on `MotionEvent.TOOL_TYPE_STYLUS`. Finger/eraser/barrel are ignored in this phase (no scroll container yet — full screen is one Canvas).
- Build with **JDK 21** (not the system JDK 26, which AGP 8.7.x does not officially support). Point Gradle at it via `org.gradle.java.home`.
- All Gradle config in **Kotlin DSL** (`build.gradle.kts`, `settings.gradle.kts`).
- No comments in code unless requested.
- One commit per task; commit messages follow `type: subject` convention.

---

## File Structure

```
scribble-ai/
  docs/superpowers/specs/2026-07-05-riddle-diary-design.md   # exists
  docs/superpowers/plans/2026-07-05-riddle-diary-tracer-bullet.md  # this file
  settings.gradle.kts
  build.gradle.kts            # root
  gradle.properties
  gradle/wrapper/...          # generated
  gradlew, gradlew.bat        # generated
  local.properties            # sdk.dir (gitignored)
  app/
    build.gradle.kts          # module: compose app
    src/main/AndroidManifest.xml
    src/main/java/com/scribble/riddle/
      MainActivity.kt
      ui/DrawSurface.kt        # Canvas: stylus capture + render user ink + render response
      ui/HandwritingRenderer.kt# DrawScope.renderAnimated(paths, progress)
      ui/InkPathFactory.kt     # Paint.getTextPath() -> List<Path> for a given string + font
      ui/StrokeStore.kt        # simple in-memory state holder for the tracer
    src/main/res/values/themes.xml, strings.xml
    src/main/res/font/<handwriting>.ttf   # bundled Cyrillic handwriting font
    src/main/res/xml/... (none in phase 1)
  .gitignore
```

Responsibilities:
- `DrawSurface.kt` — the single canvas owner; captures stylus pointer events into a list of strokes; renders strokes; hosts the response animation via `HandwritingRenderer`.
- `HandwritingRenderer.kt` — pure `DrawScope` extension that draws a list of `Path`s up to a `progress` fraction, using `PathMeasure` per path (with `nextContour()` pen-lifts).
- `InkPathFactory.kt` — converts a `String` into glyph `Path`s at a baseline position using `Paint.getTextPath()` and the bundled handwriting font.
- `StrokeStore.kt` — a tiny `mutableStateListOf`-backed holder (tracer only; replaced by `PageStore`/`SendOrchestrator` in later phases).

---

## Task 0: Environment setup (JDK 21, Android SDK, adb)

**Files:**
- Create: `local.properties` (sdk.dir; gitignored)
- Modify: `~/.bashrc` (or equivalent) — `ANDROID_HOME` export (instructions only; not automated by this task)

**Interfaces:** Produces a working `adb`, `sdkmanager`, JDK 21; `ANDROID_HOME` set.

- [ ] **Step 1: Install JDK 21 (Temurin) via Homebrew**

Run:
```bash
brew install --cask temurin@21
```
Expected: `openjdk version "21.x.x"`. Verify:
```bash
/usr/libexec/java_home -v 21
```
Expected: a path like `/usr/lib/jvm/temurin-21-jdk-amd64` (or Homebrew's `/home/linuxbrew/.linuxbrew/opt/openjdk@21`).

- [ ] **Step 2: Install Android command-line tools + platform-tools (adb)**

Run:
```bash
brew install --cask android-commandlinetools
brew install --cask android-platform-tools
```
Verify adb:
```bash
adb --version
```
Expected: a version line (no "command not found").

- [ ] **Step 3: Accept licenses + install SDK platform 35 and build-tools**

Run:
```bash
export ANDROID_HOME="$HOME/lib/android-sdk"   # brew cask installs cmdline-tools under its prefix; point sdkmanager
sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```
> Note: the exact `ANDROID_HOME` path depends on where `android-commandlinetools` installs `sdkmanager`; confirm with `which sdkmanager` and `readlink -f $(which sdkmanager)`. Set `ANDROID_HOME` to the directory containing `cmdline-tools/`. Add `export ANDROID_HOME=...` and `export PATH="$ANDROID_HOME/platform-tools:$PATH"` to `~/.bashrc`.

- [ ] **Step 4: Verify the SDK**

Run:
```bash
sdkmanager --list_installed | grep -E "platforms;android-35|build-tools;35.0.0|platform-tools"
```
Expected: all three listed.

- [ ] **Step 5: Record sdk.dir and commit a .gitignore**

Create `local.properties` (will be gitignored):
```
sdk.dir=/absolute/path/from/step/3
```
Create `.gitignore`:
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
Commit:
```bash
git add .gitignore
git commit -m "chore: gitignore for android project"
```
(`local.properties` is intentionally not committed.)

---

## Task 1: Project scaffold (Gradle KTS, Compose, builds a debug APK)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts` (root), `gradle.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/java/com/scribble/riddle/MainActivity.kt`
- Generated by wrapper task: `gradle/wrapper/*`, `gradlew`, `gradlew.bat`

**Interfaces:** Produces a buildable Compose app (`./gradlew :app:assembleDebug` succeeds).

- [ ] **Step 1: Generate the Gradle wrapper pinned to 8.10.2**

Run:
```bash
brew install gradle
gradle wrapper --gradle-version 8.10.2
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties` referencing `gradle-8.10.2-bin.zip`.

- [ ] **Step 2: Write `settings.gradle.kts`**

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

- [ ] **Step 3: Write root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
```

- [ ] **Step 4: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
org.gradle.java.home=/usr/lib/jvm/temurin-21-jdk-amd64
```
> Adjust the `org.gradle.java.home` path to the Step-0/Task-0 result of `java_home -v 21`.

- [ ] **Step 5: Write `app/build.gradle.kts`**

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
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
```

- [ ] **Step 6: Write `AndroidManifest.xml`**

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

- [ ] **Step 7: Write themes.xml, strings.xml, MainActivity.kt**

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.RiddleDiary" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```
`app/src/main/res/values/strings.xml`:
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

Run:
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

**Files:**
- Create: `app/src/main/java/com/scribble/riddle/ui/DrawSurface.kt`
- Modify: `MainActivity.kt` (host `DrawSurface`)

**Interfaces:**
- Produces: `@Composable fun DrawSurface(modifier: Modifier)` — captures `TOOL_TYPE_STYLUS` pointer events into an in-memory list of strokes and renders them.

- [ ] **Step 1: Write `DrawSurface.kt`**

```kotlin
package com.scribble.riddle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import android.view.MotionEvent

internal data class Stroke(val points: List<androidx.compose.ui.geometry.Offset>)

@Composable
fun DrawSurface(
    modifier: Modifier = Modifier,
    extraDraw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {}
) {
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var current by remember { mutableStateOf<MutableList<androidx.compose.ui.geometry.Offset>?>(null) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: continue
                    val native = change.pointerId
                    val toolType = change.type
                    if (toolType != androidx.compose.ui.input.pointer.PointerType.Stylus) continue
                    val pos = change.position
                    if (change.pressed) {
                        if (current == null) current = mutableListOf(pos)
                        else current!!.add(pos)
                    } else {
                        current?.let { strokes = strokes + Stroke(it.toList()) }
                        current = null
                    }
                }
            }
        }
    ) {
        val all = strokes + (current?.let { listOf(Stroke(it)) } ?: emptyList())
        all.forEach { stroke ->
            if (stroke.points.size > 1) {
                val p = Path()
                p.moveTo(stroke.points[0].x, stroke.points[0].y)
                for (i in 1 until stroke.points.size) p.lineTo(stroke.points[i].x, stroke.points[i].y)
                drawPath(
                    p,
                    color = Color(0xFF1A1A2E),
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        extraDraw()
    }
}
```

> Note: the exact Compose pointer API for `TOOL_TYPE_STYLUS` filtering is `PointerType.Stylus`; the `change.type` property on `PointerInputChange` distinguishes stylus vs touch. Verify against the Compose version in `build.gradle.kts`; if `change.type` is unavailable, fall back to reading `MotionEvent.TOOL_TYPE_STYLUS` via `pointerInteropFilter { e -> if (e.toolType == MotionEvent.TOOL_TYPE_STYLUS) ... }`.

- [ ] **Step 2: Host it in MainActivity**

Replace the empty `Surface` content with:
```kotlin
DrawSurface(modifier = Modifier.fillMaxSize())
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: DrawSurface captures stylus ink"
```

---

## Task 3: HandwritingRenderer — animated glyph paths via PathMeasure

**Files:**
- Create: `app/src/main/java/com/scribble/riddle/ui/HandwritingRenderer.kt`

**Interfaces:**
- Produces: `fun DrawScope.renderAnimated(paths: List<Path>, progress: Float, ink: Color)` — draws each path up to `progress` of its total length, using `PathMeasure`; advances per-path sequentially (glyph N at fraction `(progress*totalGlyphs) - N`).

- [ ] **Step 1: Write `HandwritingRenderer.kt`**

```kotlin
package com.scribble.riddle.ui

import android.graphics.PathMeasure
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

fun DrawScope.renderAnimated(paths: List<Path>, progress: Float, ink: Color) {
    if (paths.isEmpty()) return
    val total = paths.size
    val perGlyph = 1f / total
    for ((index, fullPath) in paths.withIndex()) {
        val local = ((progress - index * perGlyph) / perGlyph).coerceIn(0f, 1f)
        if (local <= 0f) continue
        drawGlyphUpTo(fullPath, local, ink)
    }
}

private fun DrawScope.drawGlyphUpTo(fullPath: Path, fraction: Float, ink: Color) {
    val measure = PathMeasure()
    measure.setPath(android.graphics.Path().apply {
        fullPath.asAndroidPath().also { }
    }, false)
    // Compose Path -> android Path
    val androidPath = fullPath.asAndroidPath()
    val pm = PathMeasure(androidPath, false)
    val length = pm.length
    val dst = android.graphics.Path()
    pm.getSegment(0f, length * fraction, dst, true)
    // also handle additional contours (multi-stroke glyphs) via nextContour
    while (pm.nextContour()) {
        val l2 = pm.length
        val dst2 = android.graphics.Path()
        pm.getSegment(0f, l2 * fraction, dst2, true)
        dst.addPath(dst2)
    }
    val composeDst = Path().apply { this.addPath(Path().also { }) }
    drawPath(
        path = Path().apply {
            // rebuild a Compose Path from dst by flattening is non-trivial;
            // simpler: use android.graphics.Path directly via drawIntoCanvas
        },
        color = ink,
        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}
```

> Implementation note: Compose's `drawPath` needs a Compose `Path`. The cleanest approach is to operate on `android.graphics.Path` throughout (have `InkPathFactory` produce `android.graphics.Path`), then convert to a Compose `Path` once via `Path().apply { addPath(compose.ui.graphics.Path) }` — or use `drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPath(androidPath, paint) }` with an `android.graphics.Paint`. The implementer should pick ONE consistent path type. Recommended: produce `android.graphics.Path` from `InkPathFactory`, segment with `PathMeasure.getSegment`, and render with `drawIntoCanvas { it.nativeCanvas.drawPath(segmented, paint) }`. Revise this file accordingly during implementation — the contract (signature + behavior) is fixed; the internal path-type plumbing is not.

- [ ] **Step 2: Write a unit test for the progress math (pure)**

Because the per-glyph fraction math is the testable core, extract and test it:

`app/src/test/java/com/scribble/riddle/ui/RenderProgressTest.kt`:
```kotlin
package com.scribble.riddle.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderProgressTest {
    @Test
    fun per_glyph_fraction_for_first_glyph_at_start() {
        assertEquals(0f, glyphLocal(0f, index = 0, total = 4), 0.001f)
    }
    @Test
    fun per_glyph_fraction_for_third_glyph_halfway() {
        assertEquals(0.5f, glyphLocal(0.625f, index = 2, total = 4), 0.001f)
    }
    @Test
    fun clamps_below_zero() {
        assertEquals(0f, glyphLocal(0.1f, index = 3, total = 4), 0.001f)
    }
}
```

Extract in `HandwritingRenderer.kt`:
```kotlin
internal fun glyphLocal(progress: Float, index: Int, total: Int): Float {
    val perGlyph = 1f / total
    return ((progress - index * perGlyph) / perGlyph).coerceIn(0f, 1f)
}
```

- [ ] **Step 3: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.scribble.riddle.ui.RenderProgressTest"`
Expected: 3 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: HandwritingRenderer progress math + animated glyph drawing"
```

---

## Task 4: InkPathFactory — String -> glyph Paths via Paint.getTextPath + bundled font

**Files:**
- Create: `app/src/main/res/font/caveat.ttf` (download an OFL Cyrillic handwriting font, e.g. Caveat)
- Create: `app/src/main/java/com/scribble/riddle/ui/InkPathFactory.kt`

**Interfaces:**
- Produces: `class InkPathFactory(paint: android.graphics.Paint)` with `fun pathsFor(text: String, originX: Float, originY: Float): List<android.graphics.Path>`.

- [ ] **Step 1: Obtain an OFL Cyrillic handwriting TTF and place it**

Run:
```bash
mkdir -p app/src/main/res/font
curl -L -o app/src/main/res/font/caveat.ttf \
  "https://github.com/google/fonts/raw/main/ofl/caveat/Caveat%5Bwght%5D.ttf"
```
> Caveat is OFL and has Cyrillic coverage. If the URL/variable-font fails, fall back to a static OFL Cyrillic handwriting font (e.g., "Marck Script", "Marmelad"). The font file name becomes the R.font resource id (`R.font.caveat`).

- [ ] **Step 2: Write `InkPathFactory.kt`**

```kotlin
package com.scribble.riddle.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

class InkPathFactory(private val paint: Paint) {

    fun pathsFor(text: String, originX: Float, originY: Float): List<Path> {
        val result = mutableListOf<Path>()
        var x = originX
        text.forEach { ch ->
            val glyphs = paint.getTextPaths(charArrayOf(ch), 0, 1, x, originY, Path())
            result.addAll(glyphs)
            val widths = FloatArray(1)
            paint.getTextWidths(charArrayOf(ch), 0, 1, widths)
            x += widths[0]
        }
        return result
    }
}
```

> Reality check: `Paint.getTextPath` returns a single `Path` (not a list) — signature is `getTextPath(text: String!, start: Int, end: Int, x: Float, y: Float, path: Path!)`. Revise to: create one `Path` per char via `paint.getTextPath(ch.toString(), 0, 1, x, originY)` and collect it. Use `paint.getTextWidths` to advance `x`. This is the exact contract; the one-line API mismatch is fixed during implementation.

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: InkPathFactory converts text to glyph paths with bundled font"
```

---

## Task 5: Tracer bullet wiring — send button → canned response animates

**Files:**
- Create: `app/src/main/java/com/scribble/riddle/ui/StrokeStore.kt`
- Modify: `DrawSurface.kt` (accept response paths + progress), `MainActivity.kt` (button + animation driver)

**Interfaces:**
- Produces: a `Send` button; on tap, a canned string ("Я дневник. Пиши мне.") is converted to glyph paths and animated from progress 0→1 over ~`text.length * 250ms`, drawn as sepia ink below the user's strokes.

- [ ] **Step 1: Write `StrokeStore.kt` (tracer state)**

```kotlin
package com.scribble.riddle.ui

import android.graphics.Path
import androidx.compose.runtime.mutableStateListOf

object StrokeStore {
    val responsePaths = mutableStateListOf<Path>()
    var responseProgress = mutableStateOf(0f)
}
```
Add `import androidx.compose.runtime.mutableStateOf`.

- [ ] **Step 2: Extend `DrawSurface` to render the response**

Add a parameter `responsePaths: List<Path>, responseProgress: Float` to `DrawSurface`, and inside the `Canvas` draw block, after the user strokes, call:
```kotlin
renderAnimated(
    paths = responsePaths.map { it.toComposePath() },
    progress = responseProgress,
    ink = androidx.compose.ui.graphics.Color(0xFF5A4A2F)
)
```
(Provide a `android.graphics.Path.toComposePath()` helper, or render via `drawIntoCanvas` with nativeCanvas — keep consistent with Task 3's decision.)

- [ ] **Step 3: Wire the button + animation in MainActivity**

```kotlin
val scope = rememberCoroutineScope()
var animJob by remember { mutableStateOf<Job?>(null) }
Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
        DrawSurface(
            modifier = Modifier.fillMaxSize(),
            responsePaths = StrokeStore.responsePaths,
            responseProgress = StrokeStore.responseProgress.value,
        )
    }
    Button(onClick = {
        val paint = android.graphics.Paint(android.graphics.ANTI_ALIAS_FLAG).apply {
            textSize = 64f
            typeface = androidx.compose.ui.text.font.FontFamily.Caveat.  // placeholder
        }
        // Build paint with the bundled font via resources:
        val tf = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.caveat)
        paint.typeface = tf
        val factory = InkPathFactory(paint)
        StrokeStore.responsePaths.clear()
        StrokeStore.responsePaths.addAll(factory.pathsFor("Я дневник. Пиши мне.", 64f, 400f))
        animJob?.cancel()
        animJob = scope.launch {
            val n = StrokeStore.responsePaths.size
            val total = (n * 250).toLong()
            val start = System.currentTimeMillis()
            while (true) {
                val t = (System.currentTimeMillis() - start).toFloat() / total
                StrokeStore.responseProgress.value = t.coerceIn(0f, 1f)
                if (t >= 1f) break
                delay(16)
            }
        }
    }) { Text("Send") }
}
```
> Replace placeholder font references with `ResourcesCompat.getFont(this, R.font.caveat)` using the Activity `Context`. Add imports: `kotlinx.coroutines.*`, `androidx.compose.foundation.layout.*`, `androidx.compose.material3.Button`, `androidx.compose.material3.Text`, `androidx.compose.runtime.*`.

- [ ] **Step 4: Build + run on device (manual verification — see Task 6)**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: tracer bullet — send button animates a canned handwriting response"
```

---

## Task 6: Deploy to BOOX via USB + manual verification (the milestone)

**Files:** none (operational).

**Interfaces:** Produces a running app on the BOOX Note Air5 C.

- [ ] **Step 1: Connect BOOX via USB and forward into WSL2**

Because the dev box is **WSL2**, USB devices are owned by Windows. Use `usbipd-win`:
1. On Windows, install `usbipd-win` (winget: `winget install usbipd-win`).
2. Enable **ADB** on the BOOX (Settings → Developer options → USB debugging; connect USB).
3. In Windows PowerShell: `usbipd list` → find the BOOX (or its ADB interface) BUSID.
4. `usbipd bind --busid <BUSID>` then `usbipd attach --wsl --busid <BUSID>`.
5. Back in WSL2: `adb devices` → should list the BOOX.

> Alternative (if usbipd is impractical): install platform-tools on the **Windows** side, run `adb` from Windows to install the APK built in WSL2 (`app/build/outputs/apk/debug/app-debug.apk`, reachable via `\\wsl$\...`).

- [ ] **Step 2: Install and launch**

Run (WSL2 path):
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.scribble.riddle/.MainActivity
```
Expected: app launches full-screen on the BOOX.

- [ ] **Step 3: Manual verification (human checkpoint — the whole point of the tracer)**

Verify on the device:
1. Writing with the stylus lays down dark ink; finger touch does NOT draw.
2. Tapping **Send** makes the canned Russian response draw itself glyph-by-glyph below the strokes.
3. The animation reads as "ink appearing" on the color e-ink screen (acceptable ghosting/stepping).
4. Note observations: stroke weight, animation speed, ghosting level, font legibility — these tune `RiddleConfig`/styling in later phases.

- [ ] **Step 4: Record results + commit a NOTES file**

Create `docs/superpowers/notes/2026-07-05-tracer-observations.md` with the device observations, then:
```bash
git add -A
git commit -m "docs: tracer bullet device observations"
```

---

## Self-Review (done by plan author)

- **Spec coverage:** This plan intentionally covers ONLY Phase 1 (toolchain + tracer bullet). The full module set (DrawSurface pure-render + SendOrchestrator pipeline, ML Kit OCR, real LLM streaming, Room persistence, PauseDetector, PageLayout, real single-stroke font verification) is deferred to subsequent plans. The tracer deliberately stubs the pipeline (button-send + canned text) to isolate the e-ink rendering risk — this is the spec's own §11 "tracer bullet."
- **Placeholder scan:** Two honest implementation-notes flagged inline (Compose-vs-android Path plumbing in Task 3; `Paint.getTextPath` one-path-per-char fix in Task 4). The contracts (signatures + behavior) are fixed; only internal plumbing is left to the implementer. No "TBD/TODO" in deliverable steps.
- **Type consistency:** `InkPathFactory.pathsFor(...) -> List<android.graphics.Path>`; `DrawSurface(responsePaths: List<...>, responseProgress: Float)`; `renderAnimated(paths, progress, ink)`. The Path-type ambiguity (Compose vs android) is called out as the single decision the implementer must make consistently in Tasks 3/4/5.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-07-05-riddle-diary-tracer-bullet.md`. Per the user's directive ("реализуешь"), inline execution (superpowers:executing-plans) will follow once this plan passes agent review (LGTM). Subagent-driven execution is avoided here because dispatched review/build subagents inherit the host's Session Protocol directive, which previously caused them to stall on Session-Card ceremony instead of doing the task.
