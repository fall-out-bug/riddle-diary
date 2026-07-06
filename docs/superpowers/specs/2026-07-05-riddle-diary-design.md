# Riddle Diary — Handwriting Chat for BOOX Note Air5 C

- **Status:** Design v4 (post-review round 3) — pending implementation plan
- **Date:** 2026-07-05
- **Proof level:** inspected
- **Target device:** BOOX Note Air5 C
- **Review:** 3-subagent review over 3 rounds; v4 closes round-3 findings
  (complete data-model appendix, `PageStore.observe -> Flow<PageSnapshot>`,
  `AwaitingConfirmation` state, PENDING-message lifecycle, Flow backpressure,
  `repeatOnLifecycle`, Room-retry owner, typed `RiddleConfig`, plus minors).

---

## 1. Overview

A native Android application that turns a BOOX Note Air5 C e-ink tablet into a
"Tom Riddle's diary": the user writes by hand on the page, and an AI replies by
"writing itself" onto the same page in synthesized handwriting strokes, as if
ink were appearing on its own.

### Target device facts (verified)

- 10.3" Kaleido 3 **color** e-ink, 300 ppi, 6 GB RAM, 64 GB storage.
- **Android 15 (API 35)** — full modern Android, Compose fully supported.
- **EMR stylus** (Wacom-type, pressure + tilt, battery-less). Standard
  `MotionEvent` / `PointerInput` APIs — **no proprietary SDK required**.
- Google Play available; ML Kit installable as a library.

### Core experience

Write on the page -> after a short writing pause, strokes are recognized
on-device -> text is streamed to an LLM -> the response is streamed back and
drawn onto the page glyph-by-glyph as handwriting. The loop is seamless; there is
no "send" button. The conversation persists as one continuous notebook.

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
- **Eraser / finger input** — v1 is stylus-only; eraser tool-type events are
  discarded (no delete gesture in v1).
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
                PageLayout ─────────────────────────► HandwritingRenderer
                (compositor; committed lines                   ▲
                 frozen during streaming)                      │ placed paths
                     │ persist raw strokes (batched per Up;     │
                     │  synth paths NOT persisted)              │
                     ▼                                         │
                   PageStore ◄───────────────────── HandwritingSynthesizer
                 (source of truth; append-only)                 ▲
                     ▲                                          │ placed paths (Flow)
                     │ request (messages incl. system msg)       │
                ConversationEngine ──request──► LlmProvider ──(LlmChunk Flow)──► ConversationEngine
                     │   ▲
                     │   │ recognized text (preContext read here too)
                     │   └────────────────┐
                     │                    │
                InkRecognizer ◄──── preContext ──── PageStore
                     │
                     │ caption StateFlow ──► DrawSurface (caption)
                     │ SendTrigger
                SendOrchestrator ◄── PauseDetector(clock) ◄── StrokeEvent
                (state machine incl. AwaitingConfirmation;
                 owns cancel + per-request CoroutineScope; exposes sendState;
                 collected under repeatOnLifecycle(STARTED))
```

Key properties:
- **Streaming everywhere:** OCR `suspend`, LLM `Flow<LlmChunk>`, synthesis
  `Flow<List<PlacedStrokePath>>`. The renderer begins glyph N while the model
  still emits N+1. **Backpressure is explicit** on each cross-module Flow:
  `extraBufferCapacity = V` with `BufferOverflow.DROP_OLDEST` on the synth edge
  (lookahead is bounded by V) and `.SUSPEND` on the LLM edge.
- **Persistence throughout the loop** (append-only): raw strokes, recognized
  text, and assistant text + synthesis seed are persisted via `PageStore` as
  produced. Raw strokes are **batched per pointer `Up`** (not per `Move`) to keep
  insert rate sane on e-ink hardware. **Raw user strokes** flow
  `DrawSurface -> PageStore`; **synthesized paths** are NOT persisted as ink —
  regenerated from `synthesisSeed` and flow `HandwritingSynthesizer -> PageLayout
  -> HandwritingRenderer`.
- **`PageStore` is the single source of truth**; `ConversationEngine` reads and
  writes through it and holds no exclusive message state.
- **One canvas owner (`DrawSurface`)**; `HandwritingRenderer` is a `DrawScope`
  primitive it invokes.
- **Caption + preContext edges:** recognized text is emitted to `DrawSurface` as a
  caption (and to `ConversationEngine`); `SendOrchestrator`/`ConversationEngine`
  reads preceding text from `PageStore` to pass as `preContext`.
- **Lifecycle:** the orchestrator's `process(...)` collector and the animation
  driver run under `repeatOnLifecycle(Lifecycle.State.STARTED)`; they pause on
  `STOPPED` (no leaked collectors / battery drain on e-ink).

### 3.2 Modules

All I/O-crossing interfaces are `suspend` or `Flow`. Time is injected for
testability. Pinned constants live in a single `RiddleConfig` (§14) consumed by
all modules.

| Module | Responsibility | Draft interface |
|---|---|---|
| **DrawSurface** | Sole canvas owner + composable entry point. Captures pointer events (**stylus only** — filters on `TOOL_TYPE_STYLUS`; finger/eraser/barrel-button `MotionEvent`s discarded); renders user and AI ink; batches strokes per `Up` before persisting. | `pointerEvents(): Flow<StrokeEvent>`; `pageState: StateFlow<PageSnapshot>` |
| **PauseDetector** | Pure logic: detects writing pause from **raw pointer events**; injectable clock. | `observe(events: Flow<StrokeEvent>, clock: MonotonicClock): Flow<SendTrigger>` |
| **SendOrchestrator** | Owns the send state machine (incl. `AwaitingConfirmation`); cancels in-flight work; single worker on `Dispatchers.Default`; collected under `repeatOnLifecycle(STARTED)`. | `fun process(triggers: Flow<SendTrigger>)`; `val sendState: Flow<SendState>`; internally `Channel<SendTrigger>(CONFLATED)`; cancels a per-request `CoroutineScope` on the cancel-window. |
| **InkRecognizer** | OCR -> text + confidence + alternatives; accepts preceding-text context. | `suspend fun recognize(batch: StrokeBatch, preContext: String?): RecognizerResult` |
| **RecognitionModelManager** | ML Kit Russian model lifecycle (availability + download + state). | `modelState: Flow<ModelState>`; `suspend fun ensureModel()` |
| **ConversationEngine** | History windowing; injects the active persona's system message as the first `Message`; drives the LLM; persists via PageStore. | `suspend fun respond(input: RecognizerResult): Flow<ConversationUpdate>` |
| **Summarizer** | Produces a running summary of older turns when the window slides; invoked by `ConversationEngine`. Pluggable seam (v1 may reuse `LlmProvider`). | `suspend fun summarize(older: List<Message>): String` |
| **LlmProvider** | Pluggable **streaming** chat seam (OpenAI-compatible now, local later). **Decoupled from persona** — system message is an ordinary first `Message`. **Gated on `privacyAcknowledged`.** | `suspend fun chat(messages: List<Message>): Flow<LlmChunk>` |
| **HandwritingSynthesizer** | Text -> stroke paths (stroke-font + jitter now, ML later); **streaming**, consuming **word-boundary text chunks**. | `fun synthesize(text: Flow<String>, seed: SynthesisSeed): Flow<List<PlacedStrokePath>>` (upstream emits word chunks); plus `synthesizeAll(text, seed): List<PlacedStrokePath>` for replay/tests. |
| **PageLayout** | Compositor: coordinate system, line-breaking, cursor, vertical flow. **Streaming-safe:** completed lines are committed (frozen); only the currently-building line is mutable. | `fun layout(content: List<Layoutable>, viewport: Rect, cursor: Cursor): List<PlacedStrokePath>` |
| **HandwritingRenderer** | `DrawScope` primitive (NOT a separate canvas); animates paths via `PathMeasure` + `nextContour()` pen-lifts; throttled to e-ink cadence. | `fun DrawScope.renderAnimated(paths: List<PlacedStrokePath>, progress: State<Float>)` |
| **EInkDisplayAdapter** | E-ink refresh seam: partial refresh (region) during animation, full refresh after each response to clear ghosting. | `fun beginPartialRefresh(region: Rect?)`; `suspend fun fullRefresh()` |
| **PageStore** | Single source of truth; Room-backed, append-only increments; **single internal retry** on transient failure; observable snapshot. | `suspend fun append(event: PageEvent): Result<Unit>`; `fun observe(): Flow<PageSnapshot>` |
| **PersonaRepository** | Persona CRUD + validation (**Room-backed**; personas are not secrets — only API keys use secure storage). Exactly one persona is active (repository invariant). | `personas: Flow<List<Persona>>`; `suspend fun active(): Persona`; `suspend fun upsert(p: Persona)` |

**Boundary principle:** each module is deep and replaceable. Explicit seams:
`LlmProvider`, `HandwritingSynthesizer`, `Summarizer`, `EInkDisplayAdapter`,
`RecognitionModelManager`, `MonotonicClock` — each has a v1 impl and a fake/test
impl.

### 3.3 Send-state machine (owned by SendOrchestrator)

```
idle ─(Up + pause)─► paused ─(cancel-window)─► recognizing
  ▲                                            │
  │                            ┌───────────────┘
  │                            ▼
cancelled ◄─(pen within    sending ─(first LlmChunk)─► streaming ─► complete
            window)           │
                              │ (0.5<=conf<0.7)
                              ▼
                        awaitingConfirmation ─(✓ / edit)─► sending
                              │
                              └─(cancel)─► idle
```

- A pen-down during the **cancel-window** cancels OCR and returns to `idle`.
- **Confidence 0.5–0.7** routes to `AwaitingConfirmation`: caption + "edit" + "✓";
  sending is gated on confirmation (or cancel returns to `idle`).
- Once the **first `LlmChunk` arrives**, the request is no longer cancel-window
  cancelable; new strokes during streaming/animating start a **follow-up turn**
  (§4.2).
- Only **one** LLM request in-flight; triggers arriving while busy are conflated
  (latest survives; latest pen state always recoverable from `DrawSurface`).

### 3.4 Stroke -> message lifecycle

- The **first `Down` of a writing block** creates a `Message(status=PENDING)` via
  `PageStore.append(UserStrokes(...))`; subsequent strokes append to that message.
- On recognition, the same message gains `recognizedText` and
  `status=RECOGNIZED`; on send `status=SENDING`; on first chunk `STREAMING`; on
  done `COMPLETE`; on failure `ERROR`. This makes the offline queue, retry, and UI
  indicators all queryable from one row.

---

## 4. UX & Data Flow

### 4.1 Send trigger — auto-send on writing pause

- Pause threshold **1500 ms** (v1; tunable).
- **Cancel-window = 800 ms** (v1; tunable 400–1500 ms). Faint "recognizing…"
  indicator; any pen-down within the window cancels and resets.
- Empty/scribble input (confidence < **0.5**) is not sent; a brief fading "…"
  marker acknowledges the drop.
- **Confidence dual-path:** `>= 0.7` -> auto-send after a brief display-only
  window; `0.5 <= confidence < 0.7` -> `AwaitingConfirmation` (caption + "edit" +
  "✓"); `< 0.5` -> discard with "…".

### 4.2 Concurrency — writing during AI response rendering

- New strokes during AI rendering start a **new writing block below** the
  rendering response; the AI animation continues independently.
- Strokes during the cancel-window cancel the pending send; strokes during
  streaming/animating contribute to the **next** turn.
- Only one LLM request in-flight; subsequent triggers are **conflated** (latest
  survives) via the orchestrator's channel.

### 4.3 Navigation — one continuous notebook

- Continuous vertical scroll. User writes near the bottom; AI replies below it;
  older entries scroll upward.
- **Auto-scroll during rendering** keeps the rendering ink near the top of the
  viewport; a manual scroll gesture during rendering cancels auto-scroll for that
  response.
- **Scroll during an in-flight animation:** already-drawn lines remain visible;
  the animation continues at its own offset; if the user releases scroll near the
  active ink, auto-scroll resumes for that response.
- **Long responses:** the renderer maintains `currentGlyphIndex`; synthesis
  produces up to **`V = 64` glyphs** ahead (a few screen lines of lookahead); if
  the user scrolls past, the index fast-forwards (past glyphs are NOT
  re-animated). Only `V` glyph-paths are held in memory.
- **Scroll-back** reconstructs completed messages from raw `stroke` (user) and
  regenerates assistant paths from `synthesisSeed`. v1 does not cache placed
  paths (acceptable for a notebook of hundreds); a bounded LRU is a noted future
  optimization.

### 4.4 The "ink appears" animation (core magic)

- `HandwritingRenderer` draws glyph-by-glyph via `PathMeasure`; multi-stroke
  glyphs use `PathMeasure.nextContour()` for brief pen-lift pauses.
- Subtle ink-spread (opacity pulse) per glyph.
- **E-ink tuning:** redraws throttled to panel-refresh cadence (<= ~1 Hz on
  Kaleido 3); a **full refresh** runs after each response (`EInkDisplayAdapter`).

### 4.5 OCR confirmation — no modal

- Recognized text is a subtle caption under the user's strokes; tap to edit.
- Mechanism: `SendOrchestrator` emits recognized text to a caption `StateFlow`
  observed by `DrawSurface` **and concurrently** launches `ConversationEngine`.

### 4.6 First-launch flow

1. **ML Kit Russian model download** (splash with progress); if offline, persist
   the requirement and persist captured strokes to Room regardless — never drop
   user input; offer "Recognize pending strokes now" once available.
2. **Privacy acknowledgment:** user must acknowledge that handwriting text leaves
   the device to the configured LLM provider. Stored as `privacyAcknowledged`;
   `LlmProvider.chat` is **gated** on it. If the user **declines**, messages stay
   `status=PENDING` and the app shows a permanent "configure provider / enable
   cloud" prompt; nothing is silently sent.
3. **LLM provider setup:** HTTPS endpoint, model name, API key, optional
   temperature/maxTokens. "Credentials not configured" is detected **distinctly
   from network errors** (not surfaced as a 401). Credentials in
   `EncryptedSharedPreferences` (Keystore).
4. Optional persona selection (default provided).

---

## 5. Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin (coroutines + Flow) | streaming end-to-end; explicit backpressure |
| UI | Jetpack Compose | single `Canvas` in `DrawSurface`; `Animatable`/`PathMeasure`; `repeatOnLifecycle` |
| `minSdk` / `targetSdk` | 31 / 35 | device is API 35 |
| Input OCR | ML Kit **Digital Ink Recognition** | Russian model, on-device; `suspend`, typically **0.5–2 s** (assumption — verify on device) |
| LLM client | OkHttp + kotlinx.serialization, OpenAI-compatible, **SSE/chunked** | behind `LlmProvider`; defaults via `RiddleConfig` (temperature=0.7, max_tokens=512); persona = first system `Message` |
| Persistence | Room (SQLite) | schema §7 |
| Handwriting (v1) | single-line **stroke** TTF, **verified open-path** + jitter; generated-stroke fallback | §8 |
| Stylus | `PointerInput`/`MotionEvent` (EMR) | pressure, tilt; `TOOL_TYPE_STYLUS` filter; barrel/eraser discarded |
| Build | Gradle (Kotlin DSL) | CLI-buildable |
| DI | manual/minimal (Hilt optional) | — |
| Security | `EncryptedSharedPreferences` + Keystore; TLS 1.2+; `usesCleartextTraffic="false"` | §9 |

---

## 6. Input language & wrong-language handling

- **Russian-only v1.** OCR uses the ML Kit Russian model. Non-Russian/mixed
  input returns low-confidence text and is discarded (< 0.5) or offered for
  inline edit — **not** routed to another language model. Explicit.

---

## 7. Persistence schema (Room)

`PageStore` is the single source of truth; append-only. (`observe()` emits full
`PageSnapshot` per change — O(N) per emission, acceptable for a v1 notebook of
hundreds of messages; a delta Flow is a noted future optimization.)

```
conversation(id PK, createdAt, personaId FK -> persona.id, systemPromptSnapshot)
            -- systemPromptSnapshot captured at the FIRST user message of the
            -- conversation; later persona edits affect only NEW conversations.
persona(id PK, name, systemPrompt, modelId?, maxTokens?, temperature?, isActive)
            -- exactly one isActive=true (uniqueness invariant enforced by repo);
            -- stored in Room; NOT a secret.
message(id PK, conversationId FK, role, displayText, recognizedText?,
        synthesisSeed?, status, createdAt, sortIndex)
            -- status: pending | recognized | sending | streaming | error | complete
stroke(id PK, messageId FK, strokeIndex, isUser, pointsJson)        -- raw points for replay
render_progress(messageId PK, charIndex, pathOffset)                -- cold-restart (1:1 with assistant msg)
conversation_summary(id PK, conversationId FK, upToMessageId, summary)  -- running summary
```

- Raw user strokes live in `stroke` (re-renderable on scroll-back).
- Assistant responses store `displayText` + `synthesisSeed`; paths regenerated
  deterministically (not persisted as ink).
- `render_progress` records how far the animation reached; on cold restart the
  response is rendered **fully drawn** at `charIndex/pathOffset` (no replay).
- `status` makes the pending/sending/error/retry lifecycle queryable.

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
  `PathMeasure.nextContour()` for pen-lifts.

### 8.2 Glyph fallback granularity

- Missing glyph -> fall back at **word (contiguous run) granularity** — never
  per-character — with a **single** cue: an **alternate ink tone** (chosen over
  an underline for lower e-ink ghosting). Synthesis consumes **word-boundary
  chunks** so word runs are detectable.

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
- **Privacy acknowledgment:** `privacyAcknowledged` flag gates `LlmProvider.chat`
  (§4.6). Handwriting text and the AI response go to the configured third-party
  LLM; OCR is on-device. A **local-only** provider path is possible (future
  `LlmProvider` impl) and the UI must not assume cloud.

---

## 10. Error Handling & Edge Cases (owners assigned)

| Case | Owner | Policy |
|---|---|---|
| Auto-send false trigger | SendOrchestrator | cancel-window (§4.1) |
| Rapid repeated triggers | SendOrchestrator | conflated channel; one in-flight request |
| OCR model not downloaded | RecognitionModelManager | download screen; persist strokes meanwhile; "Recognize pending" |
| OCR low confidence | InkRecognizer + UI | `<0.5` discard ("…"); `0.5–0.7` `AwaitingConfirmation`; `>=0.7` auto-send |
| OCR hard failure | InkRecognizer | "couldn't read the handwriting" + retry |
| No network | SendOrchestrator / ConversationEngine | queue (`status=pending`); auto-retry on connectivity |
| Credentials not configured | UI + SendOrchestrator | distinct from network error; permanent "configure provider" prompt; messages stay `pending` |
| Privacy not acknowledged | LlmProvider | `chat` gated on `privacyAcknowledged`; no silent send |
| LLM timeout (30s) / 5xx | LlmProvider | 2 retries, exp backoff (1s, 3s) |
| LLM 429 | LlmProvider | 1 retry after 30s; distinct UI |
| LLM **partial failure** (text drawn then socket died) | ConversationEngine | preserve drawn glyphs; append "…" + retry — **no full regen** (no visible jitter change) |
| LLM **empty** response | ConversationEngine | 1 auto-retry (different seed; full regen allowed); else "…" + "tap to retry" |
| Cancellation | SendOrchestrator | cancel-window cancels OCR; LLM not cancelable from window; pen-during-LLM = follow-up turn |
| Missing glyph | HandwritingSynthesizer | word-granularity fallback + alternate ink tone (§8.2) |
| Long response | HandwritingRenderer | progressive via `currentGlyphIndex` (§4.3); never block UI |
| Room write failure | PageStore | `append` does **one internal retry** then returns `Result`; transient -> snackbar; permanent -> diagnostics hint (single owner = PageStore) |
| Wrong-language input | InkRecognizer | Russian-only v1 (§6); low-confidence discard |
| **Hot backgrounding** | DrawSurface/Renderer | pause animation (`STOPPED`); resume from current position |
| **Cold restart mid-response** | PageStore + Renderer | restore from Room; render response fully-drawn at `render_progress` (no replay) |

---

## 11. Testing

### Unit (pure logic, no Android)
- **ConversationEngine** — history windowing + system-message injection, fake
  streaming `LlmProvider`. Dense coverage.
- **Summarizer** (fake) — invoked on window slide; summary persisted.
- **PauseDetector** — `observe` with `FakeMonotonicClock`.
- **SendOrchestrator** — state transitions incl. `AwaitingConfirmation`;
  cancel-window; conflation.
- **HandwritingSynthesizer** (stroke-font) — path counts; empty text;
  word-granularity fallback; seeded-jitter bounds; **determinism test** (same
  seed + same text => byte-equal paths).
- **PageLayout** — line-break/wrap; committed lines frozen during reflow.
- **HandwritingRenderer** — pure progress: path length L, `progress=0.5` =>
  offset 0.5L.
- **LlmProvider** (real) — MockWebServer, streamed OpenAI protocol;
  `privacyAcknowledged=false` blocks `chat`.
- **PageStore** — Room in-memory; `status` transitions; `append` `Result` on
  failure (single retry observed).

### UI / Compose
- Inject pointer events into `DrawSurface`; assert rendering + transitions;
  finger/barrel/eraser filtered.

### Integration — tracer bullet (vertical slice)
- Minimal end-to-end: write a word -> canned **streamed** response drawn as
  strokes. Validates the pipeline + the **feel on real e-ink**.

### Manual (human judgment)
- The "magic" — animation speed, e-ink refresh, tactile feel — verified by hand
  on the actual BOOX.

---

## 12. Open Questions (non-blocking; resolved at implementation)

1. **Specific LLM provider** — behind `LlmProvider`.
2. **Specific Cyrillic single-stroke font** — selected + verified open-path (§8.1);
   generated-stroke fallback if none.
3. Pinned constants in `RiddleConfig` (§14) are tunable on device, but the
   **contracts** (named, typed, configurable) are fixed.

---

## 13. Decisions Log

- **Approach:** Native Android over PWA/companion pipeline.
- **OCR:** ML Kit Digital Ink (on-device, Russian).
- **LLM:** **streaming** abstraction now; **decoupled from persona**; **gated on
  `privacyAcknowledged`**.
- **Handwriting:** stroke fonts + jitter now (verified open-path + fallback), ML
  later via `HandwritingSynthesizer`.
- **Persona:** configurable; `systemPrompt` **snapshotted per conversation at the
  first user message**; exactly one active.
- **Send trigger:** auto-send on pause (1500 ms) + cancel-window (800 ms) +
  `AwaitingConfirmation` for 0.5–0.7 confidence.
- **Async shape:** `suspend`/`Flow` end-to-end with explicit backpressure; one
  canvas owner; PageStore single source of truth (append-only, one internal
  retry); explicit SendOrchestrator state machine; `repeatOnLifecycle(STARTED)`.
- **Russian-only v1**; stylus-only v1 (eraser/finger/barrel discarded).

---

## 14. Appendix — Data Model & Config (boundary contracts)

```kotlin
// ---- Input / ink ----
data class StrokePoint(val x: Float, val y: Float, val tMs: Long,
                       val pressure: Float, val tilt: Float)
data class Stroke(val id: Long, val points: List<StrokePoint>)

sealed interface StrokeEvent {            // raw pointer stream from DrawSurface
    data class Down(val p: StrokePoint) : StrokeEvent
    data class Move(val p: StrokePoint) : StrokeEvent
    data class Up(val p: StrokePoint) : StrokeEvent
}

// ---- Recognition ----
data class RecognizerResult(val text: String, val confidence: Float,
                            val alternatives: List<String> = emptyList())
sealed interface ModelState { object NotDownloaded : ModelState; object Downloading : ModelState; object Ready : ModelState; data class Error(val message: String) : ModelState }

// ---- LLM streaming ----
enum class Role { SYSTEM, USER, ASSISTANT }
data class Message(val id: Long, val conversationId: Long, val role: Role,
                   val displayText: String, val recognizedText: String? = null,
                   val synthesisSeed: SynthesisSeed? = null,
                   val status: MessageStatus = MessageStatus.PENDING,
                   val createdAt: Long, val sortIndex: Int)
enum class MessageStatus { PENDING, RECOGNIZED, SENDING, STREAMING, ERROR, COMPLETE }

sealed interface LlmChunk {
    data class Text(val text: String) : LlmChunk
    object Done : LlmChunk
    data class Error(val message: String) : LlmChunk
}

sealed interface ConversationUpdate {     // ConversationEngine.respond emissions
    data class Recognized(val messageId: Long, val result: RecognizerResult) : ConversationUpdate
    data class AssistantChunk(val messageId: Long, val chunk: LlmChunk) : ConversationUpdate
    data class StatusChange(val messageId: Long, val status: MessageStatus) : ConversationUpdate
}

// ---- Handwriting / layout ----
data class SynthesisSeed(val seed: Long, val fontId: String, val jitter: Float)
data class PlacedStrokePath(val path: android.graphics.Path,
                            val origin: Pair<Float, Float>, val glyphIndex: Int)
data class Cursor(val x: Float, val y: Float)                  // PageLayout insertion point
sealed interface Layoutable {                                // PageLayout input
    data class UserInk(val strokes: List<Stroke>) : Layoutable
    data class AssistantText(val text: String, val seed: SynthesisSeed) : Layoutable
}

// ---- Orchestration ----
data class SendTrigger(val requestId: Long, val tMs: Long)
sealed interface SendState {
    object Idle : SendState
    object Paused : SendState
    object Recognizing : SendState
    object AwaitingConfirmation : SendState
    object Sending : SendState
    object Streaming : SendState
    object Cancelled : SendState
    data class Error(val reason: String) : SendState
}

// ---- Persistence events / snapshot ----
sealed interface PageEvent {              // PageStore append-only increments
    data class UserStrokes(val messageId: Long, val strokes: List<Stroke>) : PageEvent
    data class Recognized(val messageId: Long, val result: RecognizerResult) : PageEvent
    data class AssistantChunk(val messageId: Long, val chunk: LlmChunk) : PageEvent
    data class RenderProgress(val messageId: Long, val charIndex: Int, val pathOffset: Float) : PageEvent
    data class StatusChange(val messageId: Long, val status: MessageStatus) : PageEvent
}
data class PageSnapshot(val messages: List<Message>, val pendingStrokes: List<Stroke>)

// ---- Testability seams ----
interface MonotonicClock { fun nowMs(): Long }
class FakeMonotonicClock : MonotonicClock { var current = 0L; override fun nowMs() = current }

// ---- Tuning (single source of pinned constants) ----
data class RiddleConfig(
    val pauseMs: Long = 1500L,
    val cancelWindowMs: Long = 800L,
    val discardConfidence: Float = 0.5f,     // < discard
    val confirmConfidence: Float = 0.7f,     // < confirm; >= auto-send
    val synthLookaheadGlyphs: Int = 64,      // V
    val llmTimeoutMs: Long = 30_000L,
    val llmRetry5xx: Int = 2,
    val llmBackoffMs: List<Long> = listOf(1_000L, 3_000L),
    val llmRetry429DelayMs: Long = 30_000L,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
)
```
