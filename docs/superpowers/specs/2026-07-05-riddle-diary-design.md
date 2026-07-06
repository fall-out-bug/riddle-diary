# Riddle Diary — Handwriting Chat for BOOX Note Air5 C

- **Status:** Design v3 (post-review round 2) — pending implementation plan
- **Date:** 2026-07-05
- **Proof level:** inspected
- **Target device:** BOOX Note Air5 C
- **Review:** 3-subagent review; v2 addressed round-1 blockers/majors; v3 closes
  the round-2 findings (data-model appendix, decoupled LLM seam, message status,
  streaming-safe layout, Summarizer seam, pinned V, PageStore Result, stylus
  filtering, persona snapshot, diagram corrections).

---

## 1. Overview

A native Android application that turns a BOOX Note Air5 C e-ink tablet into a
"Tom Riddle's diary": the user writes by hand on the page, and an AI replies by
"writing itself" onto the same page in synthesized handwriting strokes, as if
ink were appearing on its own.

### Target device facts (verified)

- 10.3" Kaleido 3 **color** e-ink, 300 ppi, 6 GB RAM, 64 GB storage.
- **Android 15 (API 35)** — full modern Android, Compose fully supported.
- **EMR stylus** (Wacom-type, pressure + tilt, battery-less). Standard Android
  `MotionEvent` / `PointerInput` APIs — **no proprietary SDK required**.
- Google Play available; ML Kit installable as a library.

### Core experience

Write on the page -> after a short writing pause, strokes are recognized on-device
-> text is streamed to an LLM -> the response is streamed back and drawn onto the
page glyph-by-glyph as handwriting. The loop is seamless; there is no "send"
button. The conversation persists as one continuous notebook.

---

## 2. Goals & Non-Goals

### Goals

1. Seamless handwriting-in / handwriting-out conversation loop.
2. The AI reply appears as self-drawing handwriting strokes; **the first ink
   appears within ~1 s of the user finishing writing** (streaming + on-device
   OCR, not batch waits).
3. Configurable "voice" (persona) of the diary via an editable prompt.
4. On-device OCR (offline-capable); LLM via a pluggable, **streaming** provider.
5. Conversation persisted as a continuous, scrollable notebook that survives
   process death mid-response.

### Non-Goals (v1)

- ML-based handwriting synthesis (seam; deferred). v1 uses stroke-font synthesis.
- Multi-user accounts, cloud sync, backup/export.
- Recognition of anything beyond handwritten text.
- Platforms other than this Android device.
- Non-Russian handwriting input (see §6 — Russian-only v1, explicit).

---

## 3. Architecture

### 3.1 Core loop (streaming end-to-end)

```
                StrokeEvent (Down/Move/Up + t, pressure, tilt)
 STYLUS ──────────────────────► DrawSurface (single canvas owner; stylus-only)
                                   │
                     ┌─────────────┼───────────────────────────┐
                     │ (render user ink)                       │ (render AI ink via
                     ▼                                         │  DrawScope primitive)
                PageLayout ◄────────────────────────────── HandwritingRenderer
                (compositor; committed lines frozen          ▲
                 during streaming)                            │ placed paths
                     │ persist raw strokes (NOT synth paths)  │
                     ▼                                        │
                   PageStore ◄──────────────────────── HandwritingSynthesizer
                 (source of truth; append-only)                 ▲
                     ▲                                          │ placed paths (Flow)
                     │ message text                             │
                ConversationEngine ──(LlmChunk Flow)── LlmProvider (streaming seam,
                     │                                          │  decoupled from Persona)
                     │ recognized text (caption)                 │
                InkRecognizer ───caption──► DrawSurface (caption StateFlow)
                     ▲
                     │ SendTrigger (+ preContext read from PageStore)
                SendOrchestrator ◄── PauseDetector(clock) ◄── StrokeEvent
                (state machine: idle→paused→recognizing→sending→cancelled;
                 owns cancel + per-request CoroutineScope; exposes sendState)
```

Key properties:
- **Streaming everywhere:** OCR `suspend`, LLM `Flow<LlmChunk>`, synthesis
  `Flow<List<PlacedStrokePath>>`. The renderer begins glyph N while the model
  still emits N+1.
- **Persistence throughout the loop** (append-only): raw strokes, recognized
  text, and assistant text + synthesis seed are persisted via `PageStore` as
  produced. **Raw user strokes** flow `DrawSurface -> PageStore`;
  **synthesized paths** are NOT persisted as ink — they are regenerated from
  `synthesisSeed` and flow `HandwritingSynthesizer -> PageLayout ->
  HandwritingRenderer`.
- **`PageStore` is the single source of truth**; `ConversationEngine` reads and
  writes through it and holds no exclusive message state.
- **One canvas owner (`DrawSurface`)**; `HandwritingRenderer` is a `DrawScope`
  primitive it invokes.
- **Caption + preContext edges:** recognized text is emitted to `DrawSurface` as
  a caption (and to `ConversationEngine`); `SendOrchestrator` reads preceding
  text from `PageStore` to pass as `preContext` to `InkRecognizer.recognize()`.

### 3.2 Modules

All I/O-crossing interfaces are `suspend` or `Flow`. Time is injected for
testability.

| Module | Responsibility | Draft interface |
|---|---|---|
| **DrawSurface** | Sole canvas owner + composable entry point. Captures pointer events (**stylus only** — filters on `TOOL_TYPE_STYLUS`; finger/eraser/barrel-button `MotionEvent`s are discarded); renders user and AI ink. | `pointerEvents(): Flow<StrokeEvent>`; `pageState: StateFlow<PageSnapshot>` |
| **PauseDetector** | Pure logic: detects writing pause from **raw pointer events**; injectable clock. | `observe(events: Flow<StrokeEvent>, clock: MonotonicClock): Flow<SendTrigger>` |
| **SendOrchestrator** | Owns the send state machine; consumes triggers; cancels in-flight work; single worker on `Dispatchers.Default`. | `fun process(triggers: Flow<SendTrigger>)`; `val sendState: Flow<SendState>`; internally `Channel<SendTrigger>(CONFLATED)`; cancels a per-request `CoroutineScope` on the cancel-window. |
| **InkRecognizer** | OCR -> text + confidence + alternatives; accepts preceding-text context. | `suspend fun recognize(batch: StrokeBatch, preContext: String?): RecognizerResult` |
| **RecognitionModelManager** | ML Kit Russian model lifecycle (availability + download + state). | `modelState: Flow<ModelState>`; `suspend fun ensureModel()` |
| **ConversationEngine** | History windowing; injects the active persona's system message as the first `Message`; drives the LLM; persists via PageStore. | `suspend fun respond(input: RecognizerResult): Flow<ConversationUpdate>` |
| **Summarizer** | Produces a running summary of older turns when the window slides; invoked by `ConversationEngine`. Pluggable seam (v1 may reuse `LlmProvider`). | `suspend fun summarize(older: List<Message>): String` |
| **LlmProvider** | Pluggable **streaming** chat seam (OpenAI-compatible now, local later). **Decoupled from persona** — the system message is an ordinary first `Message`. | `suspend fun chat(messages: List<Message>): Flow<LlmChunk>` |
| **HandwritingSynthesizer** | Text -> stroke paths (stroke-font + jitter now, ML later); **streaming**. | `fun synthesize(text: Flow<String>, seed: SynthesisSeed): Flow<List<PlacedStrokePath>>`; plus `synthesizeAll(text, seed): List<PlacedStrokePath>` for replay/tests. |
| **PageLayout** | Compositor: coordinate system, line-breaking, cursor, vertical flow. **Streaming-safe:** completed lines are committed (frozen); only the currently-building line is mutable — a later chunk never reflows placed lines. | `fun layout(content: List<Layoutable>, viewport: Rect, cursor: Cursor): List<PlacedStrokePath>` |
| **HandwritingRenderer** | `DrawScope` primitive (NOT a separate canvas); animates paths via `PathMeasure` + `nextContour()` pen-lifts; throttled to e-ink cadence. | `fun DrawScope.renderAnimated(paths: List<PlacedStrokePath>, progress: State<Float>)` |
| **EInkDisplayAdapter** | E-ink refresh seam: partial refresh during animation, full refresh after each response to clear ghosting. | `fun beginPartialRefresh()`; `suspend fun fullRefresh()` |
| **PageStore** | Single source of truth; Room-backed, append-only increments + observable stream. | `suspend fun append(event: PageEvent): Result<Unit>`; `fun observe(): Flow<List<Message>>` |
| **PersonaRepository** | Persona CRUD + validation (**Room-backed**; personas are not secrets — only API keys use secure storage). | `personas: Flow<List<Persona>>`; `suspend fun active(): Persona`; `suspend fun upsert(p: Persona)` |

**Boundary principle:** each module is deep (much complexity behind a narrow
interface) and replaceable without breaking consumers. Explicit seams:
`LlmProvider`, `HandwritingSynthesizer`, `Summarizer`, `EInkDisplayAdapter`,
`RecognitionModelManager`, `MonotonicClock` — each has a v1 impl and a fake/test
impl.

### 3.3 Send-state machine (owned by SendOrchestrator)

```
idle ─(pointer Up + pause elapses)─► paused ─(cancel-window starts)─► recognizing
   ▲                                                                    │
   │                                              ┌─────────────────────┘
   │                                              ▼
cancelled ◄──(pen-down within cancel-window)   sending ─(first LlmChunk)─► streaming
                                                 │                          │
                                                 └─(error/empty)─► error-retry
```

- A pen-down during the **cancel-window** cancels OCR and returns to `idle`; the
  new strokes extend the current block.
- Once the **first `LlmChunk` arrives**, the request is no longer cancel-window
  cancelable; new strokes during streaming/animating start a **follow-up turn**
  (§4.2), they do not retcon the current one.
- Only **one** LLM request is in-flight; triggers arriving while busy are
  conflated (latest survives; latest pen state is always recoverable from
  `DrawSurface`).

---

## 4. UX & Data Flow

### 4.1 Send trigger — auto-send on writing pause

- Pause threshold **1500 ms** (v1; tunable on device).
- **Cancel-window = 800 ms** (v1; tunable 400–1500 ms). On trigger, a faint
  "recognizing…" indicator; any pen-down within the window cancels and resets.
- Empty/scribble input (OCR confidence < **0.5**) is not sent; a brief fading
  "…" marker acknowledges the drop.
- **Confidence dual-path:** `>= 0.7` -> auto-send after a brief display-only
  window; `0.5 <= confidence < 0.7` -> inline caption + "edit" affordance + "✓"
  confirm; sending is gated on confirmation.

### 4.2 Concurrency — writing during AI response rendering

- New strokes during AI rendering start a **new writing block below** the
  rendering response; the AI animation continues independently.
- Strokes arriving during the cancel-window cancel the pending send; strokes
  arriving during streaming/animating contribute to the **next** turn.
- Only one LLM request in-flight; subsequent triggers are **conflated** (only the
  latest survives) via the orchestrator's channel — the latest pen state is
  always recoverable from `DrawSurface`, so intermediate triggers need not be
  preserved.

### 4.3 Navigation — one continuous notebook

- Continuous vertical scroll. The user writes near the bottom; the AI replies
  below it; older entries scroll upward.
- **Auto-scroll during rendering:** keeps the rendering ink near the top of the
  viewport; a manual scroll gesture during rendering cancels auto-scroll for that
  response.
- **Long responses:** the renderer maintains `currentGlyphIndex`; synthesis
  produces up to **`V = 64` glyphs** ahead (≈ a few screen lines of lookahead —
  enough to keep the animation fed without holding a full long response in
  memory); if the user scrolls past, the index fast-forwards (past glyphs are NOT
  re-animated). Only `V` glyph-paths are held in memory; persisted strokes remain
  in Room.

### 4.4 The "ink appears" animation (core magic)

- `HandwritingRenderer` draws glyph-by-glyph via `PathMeasure`; multi-stroke
  glyphs use `PathMeasure.nextContour()` for brief pen-lift pauses.
- Subtle ink-spread (opacity pulse) per glyph.
- **E-ink tuning:** redraws throttled to panel-refresh cadence (<= ~1 Hz on
  Kaleido 3) to limit ghosting/battery; a **full refresh** runs after each
  response (`EInkDisplayAdapter`).

### 4.5 OCR confirmation — no modal

- Recognized text is a subtle caption under the user's strokes; tap to edit.
- Mechanism: `SendOrchestrator` emits recognized text to a caption `StateFlow`
  observed by `DrawSurface` **and concurrently** launches `ConversationEngine`,
  so the caption appears immediately while the LLM call proceeds.

### 4.6 First-launch flow

1. **ML Kit Russian model download** (splash with progress); if offline, persist
   the requirement and persist captured strokes to Room regardless — never drop
   user input; offer "Recognize pending strokes now" once available.
2. **LLM provider setup:** HTTPS endpoint, model name, API key, optional
   temperature/maxTokens. Credentials in `EncryptedSharedPreferences`
   (Android Keystore) — never plain prefs/Room.
3. Optional persona selection (default provided).

---

## 5. Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin (coroutines + Flow) | streaming end-to-end |
| UI | Jetpack Compose | single `Canvas` in `DrawSurface`; `Animatable`/`PathMeasure` |
| `minSdk` / `targetSdk` | 31 / 35 | device is API 35 |
| Input OCR | ML Kit **Digital Ink Recognition** | Russian model, on-device; `suspend`, typically **0.5–2 s** |
| LLM client | OkHttp + kotlinx.serialization, OpenAI-compatible, **SSE/chunked** | behind `LlmProvider`; `temperature=0.7`, `max_tokens=512` defaults; persona = first system `Message` |
| Persistence | Room (SQLite) | schema §7 |
| Handwriting (v1) | single-line **stroke** TTF, **verified open-path** + jitter; generated-stroke fallback | §8 |
| Stylus | `PointerInput`/`MotionEvent` (EMR) | pressure, tilt; `TOOL_TYPE_STYLUS` filter; barrel button discarded |
| Build | Gradle (Kotlin DSL) | CLI-buildable |
| DI | manual/minimal (Hilt optional) | — |
| Security | `EncryptedSharedPreferences` + Keystore; TLS 1.2+; `usesCleartextTraffic="false"` | §9 |

---

## 6. Input language & wrong-language handling

- **Russian-only v1.** OCR uses the ML Kit Russian model. Non-Russian/mixed
  input returns low-confidence text and is discarded (< 0.5) or offered for
  inline edit — **not** routed to another language model. Explicit, not
  accidental.

---

## 7. Persistence schema (Room)

`PageStore` is the single source of truth; append-only.

```
conversation(id PK, createdAt, personaId FK -> persona.id, systemPromptSnapshot)
            -- systemPromptSnapshot freezes the prompt for THIS conversation,
            -- so editing the persona later never retroactively changes history.
persona(id PK, name, systemPrompt, modelId?, maxTokens?, temperature?, isActive)
            -- stored in Room; NOT a secret.
message(id PK, conversationId FK, role, displayText, recognizedText?,
        synthesisSeed?, status, createdAt, sortIndex)
            -- status: pending | recognized | sending | streaming | error | complete
stroke(id PK, messageId FK, strokeIndex, isUser, pointsJson)        -- raw points for replay
render_progress(messageId FK, charIndex, pathOffset)                -- cold-restart of animation
conversation_summary(id PK, conversationId FK, upToMessageId, summary)  -- running summary
```

- Raw user strokes live in `stroke` (re-renderable on scroll-back).
- Assistant responses store `displayText` + `synthesisSeed`; paths are
  regenerated deterministically (not persisted as ink).
- `render_progress` records how far the animation reached; on cold restart the
  response is rendered **fully drawn** at `charIndex/pathOffset` (no replay).
- `status` makes the pending/sending/error/retry lifecycle queryable
  (offline queue, retry, UI indicators).

### History windowing (ConversationEngine)

- Sliding window of the **last N turns** + a **running summary** of older turns
  sent to the LLM; caps tokens, avoids `context_length_exceeded`.
- The summary is produced by the `Summarizer` seam and persisted in
  `conversation_summary` (advanced as the window slides); summarize only on slide
  to bound cost. N and the summary are observable (`historySize(): Int`).

---

## 8. Handwriting synthesis feasibility (key risk)

### 8.1 Stroke-font verification (must do before core work)

`Paint.getTextPath()` returns **closed outline contours** for standard TTFs —
tracing those animates "drawing the outline," not pen writing. The chosen font
**must be verified on-device** via `getTextPath()` to produce true **open
single-stroke paths**.

- Candidate single-stroke fonts (Hershey-derived / "single-line" engraving fonts)
  must be checked for **full Cyrillic coverage** and open paths.
- **Fallback if no suitable TTF exists:** programmatically generated stroke data
  per Cyrillic character (open handwriting datasets or centerline decomposition).
- **Multi-stroke glyphs** (e.g. "Д", "Ж", "Й") are segmented by
  `PathMeasure.nextContour()` so the renderer inserts pen-lifts.

### 8.2 Glyph fallback granularity

- Missing glyph -> fall back at **word (contiguous run) granularity** — never
  per-character — with a subtle visual cue (faint underline or alternate ink
  tone).

### 8.3 Font licensing

- Bundled font must be **open-licensed (OFL/CC0/Apache)**; recorded in a
  `NOTICE`/third-party-licenses screen.

---

## 9. Security & Privacy

- **Credentials** (LLM API key, endpoint) in `EncryptedSharedPreferences` backed
  by the **Android Keystore**. Never plain prefs, Room, or logs. (Personas are
  not secrets and live in Room.)
- **Transport:** TLS 1.2+; `android:usesCleartextTraffic="false"`; HTTPS-only
  base URLs enforced on save.
- **Logging:** no headers/bodies/keys logged. Any HTTP interceptor is
  **debug-build only** with `Authorization` redacted; redaction covers crash logs
  and analytics.
- **Privacy disclosure (first launch + settings):** handwriting text and the AI
  response are sent to the configured third-party LLM provider; OCR is
  on-device. Opt-in acknowledgment required before the first cloud request. A
  **local-only** provider path is possible (future `LlmProvider` impl) and the UI
  must not assume cloud.

---

## 10. Error Handling & Edge Cases (owners assigned)

| Case | Owner | Policy |
|---|---|---|
| Auto-send false trigger | SendOrchestrator | cancel-window (§4.1) |
| Rapid repeated triggers | SendOrchestrator | conflated channel; one in-flight request |
| OCR model not downloaded | RecognitionModelManager | download screen; persist strokes meanwhile; "Recognize pending" affordance |
| OCR low confidence | InkRecognizer + UI | `<0.5` discard (fading "…"); `0.5–0.7` inline-edit gate; `>=0.7` auto-send |
| OCR hard failure | InkRecognizer | "couldn't read the handwriting" + retry |
| No network | SendOrchestrator / ConversationEngine | queue (`status=pending`); "no connection — will send when back"; auto-retry on connectivity |
| LLM timeout (30s) / 5xx | LlmProvider | 2 retries, exp backoff (1s, 3s) |
| LLM 429 | LlmProvider | 1 retry after 30s; distinct UI ("you're writing fast") |
| LLM empty response | ConversationEngine | 1 auto-retry (different seed); else draw "…" ink + inline "tap to retry" |
| Cancellation | SendOrchestrator | cancel-window cancels OCR; LLM not cancelable from window; pen-during-LLM = follow-up turn |
| Missing glyph | HandwritingSynthesizer | word-granularity fallback + cue (§8.2) |
| Long response | HandwritingRenderer | progressive via `currentGlyphIndex` (§4.3); never block UI |
| Room write failure | PageStore | `append` returns `Result`; transient -> retry + small snackbar; permanent -> diagnostics/export hint |
| Wrong-language input | InkRecognizer | Russian-only v1 (§6); low-confidence discard |
| **Hot backgrounding** | DrawSurface/Renderer | pause animation; resume from current position |
| **Cold restart mid-response** | PageStore + Renderer | restore from Room; render response fully-drawn at `render_progress` (no replay) |

---

## 11. Testing

### Unit (pure logic, no Android)
- **ConversationEngine** — history windowing + system-message injection, with a
  fake streaming `LlmProvider` (`Flow<LlmChunk>`). Core logic; dense coverage.
- **Summarizer** (fake impl) — invoked on window slide; assert summary persisted.
- **PauseDetector** — `observe` with `FakeMonotonicClock`; assert timings.
- **SendOrchestrator** — state-machine transitions; cancel-window cancellation;
  conflation behavior.
- **HandwritingSynthesizer** (stroke-font impl) — path counts, empty text,
  word-granularity fallback, seeded-jitter bounds.
- **PageLayout** — line-break/wrap; assert committed lines are frozen during
  streaming reflow.
- **HandwritingRenderer** — pure progress test: given a path of length L and
  `progress=0.5`, emits frame at offset 0.5L.
- **LlmProvider** (real impl) — MockWebServer against streamed OpenAI protocol.
- **PageStore** — Room in-memory (Robolectric); assert `status` transitions and
  `append` `Result` on failure.

### UI / Compose
- Inject pointer events into `DrawSurface`; assert rendering + state transitions;
  assert finger/barrel events are filtered out.

### Integration — tracer bullet (vertical slice)
- Minimal end-to-end: write a word -> canned **streamed** response drawn as
  strokes. Validates the pipeline + the **feel on real e-ink**.

### Manual (human judgment)
- The "magic" — animation speed, e-ink refresh, tactile feel — verified by hand
  on the actual BOOX.

---

## 12. Open Questions (non-blocking; resolved at implementation)

1. **Specific LLM provider** — behind `LlmProvider`; chosen at implementation.
2. **Specific Cyrillic single-stroke font** — selected + verified open-path at
   implementation (§8.1); generated-stroke fallback if none.
3. The **values** of pause/cancel-window/confidence floors are tunable on device,
   but the **contracts** (that they exist and are configurable) are fixed (§4.1).

---

## 13. Decisions Log

- **Approach:** Native Android over PWA/companion pipeline — seamless magic.
- **OCR:** ML Kit Digital Ink (on-device, Russian).
- **LLM:** **streaming** abstraction now, concrete provider later; **decoupled
  from persona** (system message is an ordinary first `Message`).
- **Handwriting:** stroke fonts + jitter now (verified open-path + fallback), ML
  later via `HandwritingSynthesizer`.
- **Persona:** configurable; default = friendly "magic diary companion";
  `systemPrompt` **snapshotted per conversation** so edits don't rewrite history.
- **Send trigger:** auto-send on pause (1500 ms) + cancel-window (800 ms).
- **Async shape:** `suspend`/`Flow` end-to-end; one canvas owner; PageStore as
  single source of truth (append-only); explicit SendOrchestrator state machine.
- **Russian-only v1** for OCR.

---

## 14. Appendix — Data Model (boundary contracts)

```kotlin
data class StrokePoint(val x: Float, val y: Float, val tMs: Long,
                       val pressure: Float, val tilt: Float)
data class Stroke(val id: Long, val points: List<StrokePoint>)

sealed interface StrokeEvent {            // raw pointer stream from DrawSurface
    data class Down(val p: StrokePoint) : StrokeEvent
    data class Move(val p: StrokePoint) : StrokeEvent
    data class Up(val p: StrokePoint) : StrokeEvent
}

data class RecognizerResult(val text: String, val confidence: Float,
                            val alternatives: List<String> = emptyList())

sealed interface LlmChunk {               // streaming LLM output
    data class Text(val text: String) : LlmChunk
    object Done : LlmChunk
    data class Error(val message: String) : LlmChunk
}

data class SynthesisSeed(val seed: Long, val fontId: String, val jitter: Float)

data class PlacedStrokePath(val path: android.graphics.Path,
                            val origin: Pair<Float, Float>,
                            val glyphIndex: Int)

sealed interface SendState {
    object Idle : SendState
    object Paused : SendState
    object Recognizing : SendState
    object Sending : SendState
    object Streaming : SendState
    object Cancelled : SendState
    data class Error(val reason: String) : SendState
}

sealed interface PageEvent {              // PageStore append-only increments
    data class UserStrokes(val messageId: Long, val strokes: List<Stroke>) : PageEvent
    data class Recognized(val messageId: Long, val result: RecognizerResult) : PageEvent
    data class AssistantChunk(val messageId: Long, val chunk: LlmChunk) : PageEvent
    data class RenderProgress(val messageId: Long, val charIndex: Int, val pathOffset: Float) : PageEvent
    data class StatusChange(val messageId: Long, val status: MessageStatus) : PageEvent
}

enum class MessageStatus { PENDING, RECOGNIZED, SENDING, STREAMING, ERROR, COMPLETE }

data class Cursor(val x: Float, val y: Float)   // PageLayout insertion point
data class PageSnapshot(val messages: List<Message>, val pendingStrokes: List<Stroke>)
```
