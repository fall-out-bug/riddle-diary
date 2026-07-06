# Riddle Diary — Handwriting Chat for BOOX Note Air5 C

- **Status:** Approved design — pending implementation plan
- **Date:** 2026-07-05
- **Proof level:** not_assessed (design phase)
- **Target device:** BOOX Note Air5 C

---

## 1. Overview

A native Android application that turns a BOOX Note Air5 C e-ink tablet into a
"Tom Riddle's diary": the user writes by hand on the page, and an AI replies by
"writing itself" onto the same page in synthesized handwriting strokes, as if
ink were appearing on its own.

### Target device facts (verified)

- 10.3" Kaleido 3 **color** e-ink, 300 ppi, 6 GB RAM, 64 GB storage.
- **Android 15 (API 35)** — full modern Android, Compose fully supported.
- **EMR stylus** (Wacom-type, pressure + tilt, battery-less). Exposed to apps
  through standard Android `MotionEvent` / `PointerInput` APIs — **no
  proprietary SDK required**.
- Google Play available; ML Kit can be installed as a library.

### Core experience

Write on the page -> after a short writing pause, strokes are recognized on-device
-> text is sent to an LLM -> the response is drawn back onto the page
stroke-by-stroke as handwriting. The whole loop is seamless; there is no "send"
button. The conversation persists as one continuous notebook.

---

## 2. Goals & Non-Goals

### Goals

1. Seamless handwriting-in / handwriting-out conversation loop.
2. The AI reply appears as self-drawing handwriting strokes ("ink appears").
3. Configurable "voice" (persona) of the diary via an editable prompt.
4. On-device OCR (offline-capable); LLM via a pluggable provider abstraction.
5. Conversation persisted as a continuous, scrollable notebook.

### Non-Goals (v1)

- ML-based handwriting synthesis (architected as a hook; deferred to a later
  version). v1 renders with stroke-font synthesis.
- Multi-user accounts, cloud sync, backup.
- Recognition of anything beyond handwritten text (no drawings/diagrams).
- Platforms other than this Android device.

---

## 3. Architecture

### 3.1 Core loop

```
 STYLUS --> DrawSurface --(strokes Flow)--> PauseDetector
                                              |  pause ~1.5s + cancel-window
                                              v  SendTrigger
                                           InkRecognizer  (ML Kit Digital Ink, ru, on-device)
                                              |  text
                                              v
                                           ConversationEngine  (history + persona prompt)
                                              |  request
                                              v
                                           LlmProvider  (abstraction)
                                              |  response text
                                              v
                                           HandwritingSynthesizer  (text -> StrokePaths)
                                              |  paths
                                              v
                                           HandwritingRenderer  (animated ink on Canvas)
                                              |
                                              v
                                           PageStore  (Room persistence)
```

### 3.2 Modules (deep, isolated, independently testable)

| Module | Responsibility | Draft interface |
|---|---|---|
| **DrawSurface** | Capture stylus strokes and render the user's own ink on a Compose `Canvas`. | `captureStrokes(): Flow<Stroke>` |
| **PauseDetector** | Pure logic: detect a writing pause and emit a send trigger. Fake-clock testable. | `observe(strokes): Flow<SendTrigger>` |
| **InkRecognizer** | OCR of strokes -> text. Wraps ML Kit Digital Ink Recognition (Russian model, on-device). | `recognize(input): RecognizerResult` |
| **ConversationEngine** | Holds message history and the persona prompt; orchestrates a response. | `respond(userText): String` |
| **LlmProvider** | Chat abstraction. Pluggable: OpenAI-compatible now, local model later. | `chat(messages): String` |
| **HandwritingSynthesizer** | Text -> stroke paths. Default impl = single-line stroke font + jitter; ML impl later. | `synthesize(text): List<StrokePath>` |
| **HandwritingRenderer** | Stroke paths -> animated ink drawn on the canvas (`PathMeasure` + ink-spread). | `render(paths, target): Animation` |
| **PageStore** | Persistence of conversation / messages / pages (Room/SQLite). | `load() / save(page)` |
| **PersonaConfig** | Editable system prompt defining the diary's "voice". | `systemPrompt(): String` |

**Boundary principle:** every module is understandable without reading its
internals and replaceable without breaking its consumers. In particular
`LlmProvider` and `HandwritingSynthesizer` are explicit seams: v1 ships default
implementations, and richer ones can be dropped in later with no change to the
modules that depend on them.

---

## 4. UX & Data Flow

### Send trigger — auto-send on writing pause

- After ~1.5 s (configurable) with no new strokes, recognition + send triggers.
- **Cancel-window:** on trigger, a brief "recognizing..." indicator is shown. Any
  new pen input within that window cancels the send and resets the pause timer.
  This keeps the experience seamless while protecting against mid-thought false
  triggers.
- Empty / scribble input (very low OCR confidence) is not sent; it is quietly
  discarded.

### Navigation — one continuous notebook

- Continuous vertical scroll. The user writes near the bottom; the AI replies
  below it; older entries scroll upward. There are no separate "chat screens" —
  the whole conversation is one diary.

### The "ink appears" animation (core magic)

- The synthesized stroke paths are drawn glyph-by-glyph following each path via
  `PathMeasure`, at roughly handwriting speed.
- A subtle ink-spread effect (opacity pulse / slight blur) per glyph.
- e-ink tuning: on Kaleido 3 the screen refresh is slower, so the animation is
  tuned slower on purpose — the refresh artifacts actually reinforce the
  "text soaking through the page" feeling. Speed is configurable.

### OCR confirmation — no modal

- Recognized text is shown as a subtle caption under the user's strokes. Tapping
  it allows editing.
- Low-confidence recognition offers inline editing before the message is sent.

---

## 5. Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin | — |
| UI | Jetpack Compose | `Canvas` for DrawSurface; `Animatable` / `PathMeasure` for the ink animation |
| `minSdk` | 31 (Android 12) | device is API 35; comfortable margin, full modern API access |
| `targetSdk` | 35 (Android 15) | matches the device |
| Input OCR | ML Kit **Digital Ink Recognition** (`com.google.mlkit:digital-ink-recognition`) | works on strokes directly (not bitmaps); Russian supported; on-device |
| LLM client | OkHttp + kotlinx.serialization, OpenAI-compatible protocol | behind the `LlmProvider` interface |
| Persistence | Room (SQLite) | tables: `Conversation`, `Message`, `Page` (strokes + text) |
| Handwriting (v1) | bundled single-line (stroke) TTF with Cyrillic glyphs + jitter transform | drawn via `PathMeasure` |
| Stylus | standard `PointerInput` / `MotionEvent` (EMR) | pressure & tilt available |
| Build | Gradle (Kotlin DSL) | CLI-buildable, Android Studio optional |
| DI | manual / minimal (Hilt optional) | keep it simple |

**Key assumption (flagged, agreed):** v1 renders handwriting via **stroke fonts +
jitter + PathMeasure animation**. This already draws *actual* pen strokes (not a
filled typeface) and is on-device and instant. Full ML-based stroke synthesis
(neural generation of stroke trajectories from text) is deferred — the
`HandwritingSynthesizer` interface is the hook so it can be added later without
rewriting consumers.

**e-ink atmosphere (near-free):** Kaleido 3 color allows a faint sepia/ochre
"old paper" page background, which strengthens the diary mood at negligible cost.

---

## 6. Error Handling & Edge Cases

### Auto-send
- Mid-thought false trigger -> cancel-window (see Section 4).
- Rapid repeated triggers -> debounce / single in-flight request queue.

### OCR (ML Kit)
- The Russian handwriting model must be downloaded separately. On first launch,
  check for / download it; if offline at that moment, show a clear instruction.
- Low confidence -> inline edit before sending (see Section 4).
- Hard recognition failure -> "couldn't read the handwriting" + retry.

### LLM / network
- No network -> message is queued; "no connection — will send when back"; auto
  retry on connectivity restore.
- Timeout / model error -> inline error note in the diary + retry control.
- Empty model response -> graceful placeholder + retry.

### Synthesis / rendering
- Missing glyph in the stroke font (rare for Cyrillic) -> fall back to the system
  font for that character.
- Long responses -> render progressively as the user scrolls; never block the UI.

### Lifecycle
- Backgrounding mid-response -> persist partial state; resume on return.
- The entire conversation is in Room and is restored on launch.

---

## 7. Testing

### Unit tests (pure logic, no Android)
- **ConversationEngine** — history management, persona-prompt assembly, with a
  fake (deterministic) `LlmProvider`. This is the core logic; cover it densely.
- **PauseDetector** — extract as pure logic; test timings with a fake clock.
- **HandwritingSynthesizer** (stroke-font impl) — correct path count, empty
  text, glyph fallback, and jitter staying within bounds (seeded RNG).
- **LlmProvider** (real impl) — via MockWebServer against the OpenAI-compatible
  protocol.

### Room
- In-memory DB tests (Robolectric).

### UI / Compose
- Inject pointer events into `DrawSurface`; assert trigger and rendering.

### Integration — tracer bullet (vertical slice)
- An early minimal end-to-end: write a word -> receive a canned response drawn as
  strokes. This validates the whole pipeline and, critically, the **feel on real
  e-ink** before the full feature set is built.

### Manual verification (human judgment)
- The "magic" — animation speed, e-ink refresh behavior, tactile feel — is
  subjective and can only be verified by hand on the actual BOOX device. This is
  an explicitly human checkpoint, not automated.

---

## 8. Open Questions (non-blocking, resolved at implementation)

1. **Specific LLM provider** — deferred behind `LlmProvider`; choose during
   implementation (OpenAI-compatible / GigaChat / YandexGPT / local).
2. **Specific Cyrillic stroke font** — selected during implementation from
   single-line fonts with full Cyrillic coverage.
3. **Timing/confidence thresholds** — pause length, cancel-window, OCR
   confidence floor — tuned on the real device.

---

## 9. Decisions Log

- **Approach:** Native Android (Approach A) over a web PWA or a companion
  pipeline — chosen for the seamless, maximally "magical" loop; BOOX is standard
  Android, so no proprietary SDK is needed.
- **OCR:** ML Kit Digital Ink (on-device, Russian) over cloud OCR — for offline
  operation and instant recognition.
- **LLM:** abstraction now, concrete provider later — keeps options open.
- **Handwriting rendering:** stroke fonts + jitter now, ML synthesis later (via
  the `HandwritingSynthesizer` seam).
- **Persona:** configurable (editable prompt), default = a friendly "magic diary
  companion"; the literal Riddle voice is one possible configuration.
- **Send trigger:** auto-send on pause (with cancel-window) over an explicit
  button — chosen for the seamless diary feel.
