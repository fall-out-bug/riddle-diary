# Riddle Diary — Handwriting Chat for BOOX Note Air5 C

- **Status:** Design v2 (post-review) — pending implementation plan
- **Date:** 2026-07-05
- **Proof level:** inspected
- **Target device:** BOOX Note Air5 C
- **Review:** v1 reviewed by 3 subagents (deepseek/kimi/minimax); v2 addresses all blocker/major findings.

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
   appears within ~1 s of the user finishing writing** (driven by streaming +
   on-device OCR, not batch waits).
3. Configurable "voice" (persona) of the diary via an editable prompt.
4. On-device OCR (offline-capable); LLM via a pluggable, **streaming** provider.
5. Conversation persisted as a continuous, scrollable notebook that survives
   process death mid-response.

### Non-Goals (v1)

- ML-based handwriting synthesis (architected as a seam; deferred). v1 renders
  with stroke-font synthesis.
- Multi-user accounts, cloud sync, backup/export.
- Recognition of anything beyond handwritten text (no drawings/diagrams).
- Platforms other than this Android device.
- Non-Russian handwriting input (see §6 — Russian-only v1, explicitly).

---

## 3. Architecture

### 3.1 Core loop (streaming end-to-end)

```
                StrokeEvent (Down/Move/Up + t, pressure, tilt)
 STYLUS ──────────────────────► DrawSurface (single canvas owner)
                                   │
                     ┌─────────────┼───────────────────────────┐
                     │ (render user ink)                       │ (render AI ink via
                     ▼                                         │  DrawScope primitive)
                PageLayout ◄────────────────────────────── HandwritingRenderer
                (compositor: line-wrap, cursor, placement)     ▲
                     │                                          │ placed paths
                     │ persist raw strokes                      │
                     ▼                                          │
                   PageStore ◄─────────────────────────── HandwritingSynthesizer
                 (source of truth,                                ▲
                  persist throughout loop)          placed/coming paths (Flow)
                     ▲                                          │
                     │ message text                              │
                ConversationEngine ──(LlmChunk Flow)── LlmProvider (streaming seam)
                     ▲                                          │
                     │ recognized text                          │ requests
                InkRecognizer ◄────────────────────────────────┘
                     ▲
                     │ SendTrigger
                SendOrchestrator  ◄── PauseDetector(clock) ◄── StrokeEvent
                (state machine:
                 idle→paused→recognizing→sending→cancelled;
                 owns cancel + per-request CoroutineScope;
                 exposes sendState: Flow<SendState>)
```

Key properties vs. v1:
- **Streaming everywhere:** OCR `suspend`, LLM `Flow<LlmChunk>`, synthesis
  `Flow<List<PlacedStrokePath>>`. The renderer begins glyph N while the model
  still emits N+1.
- **Persistence happens throughout the loop**, not only at the end: raw strokes,
  recognized text, and assistant text+synthesis seed are all persisted via
  `PageStore` as they are produced.
- **`PageStore` is the single source of truth**; `ConversationEngine` reads and
  writes through it and holds no exclusive message state.
- **One canvas owner (`DrawSurface`)**; `HandwritingRenderer` is a `DrawScope`
  rendering primitive it invokes.

### 3.2 Modules

All I/O-crossing interfaces are `suspend` or `Flow`. Time is injected for
testability. Draft signatures are Kotlin.

| Module | Responsibility | Draft interface |
|---|---|---|
| **DrawSurface** | Sole canvas owner + composable entry point. Captures pointer events; renders both user and AI ink. | `pointerEvents(): Flow<StrokeEvent>`; exposes `pageState: StateFlow<PageSnapshot>` for rendering. |
| **PauseDetector** | Pure logic: detect writing pause from **raw pointer events** (not completed strokes); injectable clock. | `observe(events: Flow<StrokeEvent>, clock: MonotonicClock): Flow<SendTrigger>` |
| **SendOrchestrator** | Owns the send state machine; consumes events + triggers; cancels in-flight work; single worker. | `sendState: Flow<SendState>`; `events: Channel<SendTrigger>(CONFLATED)` on `Dispatchers.Default`; cancels a per-request `CoroutineScope` on the cancel-window. |
| **InkRecognizer** | OCR of strokes -> text + confidence + alternatives; accepts preceding-text context. | `suspend fun recognize(batch: StrokeBatch, preContext: String?): RecognizerResult` |
| **RecognitionModelManager** | ML Kit Russian model lifecycle (availability + download + state). | `modelState: Flow<ModelState>`; `suspend fun ensureModel(): Unit` |
| **ConversationEngine** | History windowing + persona; drives the LLM; persists via PageStore. | `suspend fun respond(input: RecognizerResult): Flow<ConversationUpdate>` |
| **LlmProvider** | Pluggable **streaming** chat seam (OpenAI-compatible now, local later). | `suspend fun chat(messages: List<Message>, persona: Persona): Flow<LlmChunk>` |
| **HandwritingSynthesizer** | Text -> stroke paths (stroke-font + jitter now, ML later); **streaming**. | `fun synthesize(text: Flow<String>, seed: SynthesisSeed): Flow<List<PlacedStrokePath>>`; plus `synthesizeAll(text, seed): List<PlacedStrokePath>` for replay/tests. |
| **PageLayout** | Compositor: coordinate system, line-breaking, cursor, vertical flow; places synthesized strokes. | `suspend fun layout(content: List<Layoutable>, viewport: Rect, cursor: Cursor): List<PlacedStrokePath>` |
| **HandwritingRenderer** | `DrawScope` rendering primitive (NOT a separate canvas); animates paths via `PathMeasure` + `nextContour()` pen-lifts; throttled to e-ink cadence. | `fun DrawScope.renderAnimated(paths: List<PlacedStrokePath>, progress: State<Float>)` |
| **EInkDisplayAdapter** | E-ink refresh strategy seam: partial refresh during animation, full refresh after each response to clear ghosting. | `fun beginPartialRefresh()`; `suspend fun fullRefresh()` (v1: real on BOOX; injectable no-op in tests). |
| **PageStore** | Single source of truth; Room-backed persistence + observable stream. | `suspend fun save(snapshot: PageSnapshot)`; `fun observe(): Flow<List<Message>>` |
| **PersonaRepository** | Persona CRUD + validation + secure storage. | `personas: Flow<List<Persona>>`; `suspend fun active(): Persona`; `suspend fun upsert(p: Persona)` |

**Boundary principle:** each module is deep (much complexity hidden behind a
narrow interface) and replaceable without breaking consumers. Explicit seams:
`LlmProvider`, `HandwritingSynthesizer`, `EInkDisplayAdapter`,
`RecognitionModelManager`, `MonotonicClock` — all have v1 implementations and
fake/test implementations.

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

- A pen-down during the **cancel-window** cancels OCR (it has not started network
  work) and returns to `idle`; the new strokes extend the current block.
- Once the **first `LlmChunk` arrives**, the request is no longer cancel-window
  cancelable; new strokes during streaming/animating start a **follow-up turn**
  (see §4.2), they do not retcon the current one.
- Only **one** LLM request is in-flight; triggers arriving while busy are
  conflated (latest pen state is always recoverable from `DrawSurface`).

---

## 4. UX & Data Flow

### 4.1 Send trigger — auto-send on writing pause

- Pause threshold **1500 ms** (v1; tunable on device).
- **Cancel-window = 800 ms** (v1; tunable 400–1500 ms). On trigger, a faint
  "recognizing…" indicator is shown; any pen-down within the window cancels and
  resets the timer.
- Empty/scribble input (OCR confidence < **0.5**) is not sent; a brief fading
  "…" marker acknowledges the drop so the app never looks broken.
- **Confidence dual-path:** `confidence >= 0.7` -> auto-send after a brief
  display-only window; `0.5 <= confidence < 0.7` -> inline caption with a small
  "edit" affordance + "✓" confirm; sending is gated on confirmation.

### 4.2 Concurrency — writing during AI response rendering

- New strokes during AI rendering start a **new writing block below** the
  rendering response; the AI animation continues independently.
- Strokes arriving during the cancel-window cancel the pending send; strokes
  arriving during streaming/animating contribute to the **next** turn.
- Only one LLM request in-flight; subsequent input is queued via the
  orchestrator's conflated channel.

### 4.3 Navigation — one continuous notebook

- Continuous vertical scroll. The user writes near the bottom; the AI replies
  below it; older entries scroll upward.
- **Auto-scroll during rendering:** the view keeps the currently-rendering ink
  near the top of the viewport. A manual scroll gesture during rendering cancels
  auto-scroll for that response and returns control to the user.
- **Long responses:** the renderer maintains `currentGlyphIndex`; synthesis
  produces up to `V` glyphs ahead; if the user scrolls past, the index
  fast-forwards (past glyphs are NOT re-animated). Only `V` glyph-paths are held
  in memory; persisted strokes remain in Room.

### 4.4 The "ink appears" animation (core magic)

- `HandwritingRenderer` draws glyph-by-glyph following each path via
  `PathMeasure`; multi-stroke glyphs use `PathMeasure.nextContour()` to insert
  brief pen-lift pauses (no connecting artifacts).
- Subtle ink-spread (opacity pulse) per glyph.
- **E-ink tuning:** redraws are throttled to panel-refresh cadence (<= ~1 Hz on
  Kaleido 3) to limit ghosting and battery burn; stepped artifacts are accepted
  as part of the aesthetic. A **full refresh** runs after each response to clear
  accumulated ghosting (`EInkDisplayAdapter`).

### 4.5 OCR confirmation — no modal

- Recognized text is a subtle caption under the user's strokes; tap to edit.
- The feedback edge `InkRecognizer -> DrawSurface (caption) -> ConversationEngine`
  is explicit (see §3.1): recognized text reaches the UI before the engine.

### 4.6 First-launch flow

1. **ML Kit Russian model download** (splash with progress); if offline, persist
   the requirement and persist captured strokes to Room regardless — never drop
   user input; offer "Recognize pending strokes now" once the model is available.
2. **LLM provider setup screen:** endpoint (HTTPS base URL), model name, API key,
   optional temperature/maxTokens. Credentials are stored in
   `EncryptedSharedPreferences` (Android Keystore) — never plain prefs or Room.
3. Optional persona selection (default persona provided).

---

## 5. Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin (coroutines + Flow) | streaming end-to-end |
| UI | Jetpack Compose | single `Canvas` in `DrawSurface`; `Animatable`/`PathMeasure` for ink animation |
| `minSdk` / `targetSdk` | 31 / 35 | device is API 35 |
| Input OCR | ML Kit **Digital Ink Recognition** (`com.google.mlkit:digital-ink-recognition`) | Russian model, on-device; recognition is `suspend` and typically **0.5–2 s** (fast, not "instant") |
| LLM client | OkHttp + kotlinx.serialization, OpenAI-compatible, **SSE/chunked streaming** | behind `LlmProvider`; `temperature=0.7`, `max_tokens=512` defaults; persona as first system message |
| Persistence | Room (SQLite) | schema in §7 |
| Handwriting (v1) | single-line **stroke** TTF with Cyrillic, **verified open-path** + jitter; generated-stroke fallback | see §8.1 |
| Stylus | `PointerInput`/`MotionEvent` (EMR) | pressure, tilt; barrel button ignored in v1 |
| Build | Gradle (Kotlin DSL) | CLI-buildable |
| DI | manual/minimal (Hilt optional) | keep simple |
| Security | `EncryptedSharedPreferences` + Android Keystore; TLS 1.2+; `usesCleartextTraffic="false"` | see §9 |

---

## 6. Input language & wrong-language handling

- **Russian-only v1.** OCR uses the ML Kit Russian model. Non-Russian/mixed
  input returns low-confidence text and is discarded (< 0.5) or offered for
  inline edit — it is **not** routed to another language model. This is
  explicit, not accidental.

---

## 7. Persistence schema (Room)

`PageStore` is the single source of truth. Every entity below is written as it is
produced (strokes on pointer-up batches; recognized text on recognition;
assistant text + synthesis seed on chunks). ER:

```
conversation(id PK, createdAt, personaId FK -> persona.id)
persona(id PK, name, systemPrompt, modelId?, maxTokens?, temperature?, isActive)
message(id PK, conversationId FK, role, displayText, recognizedText?,
        synthesisSeed?, createdAt, sortIndex)
stroke(id PK, messageId FK, strokeIndex, isUser, pointsJson)   -- raw points for replay
render_progress(messageId FK, charIndex, pathOffset)           -- cold-restart of animation
```

- Raw user strokes live in `stroke` (re-renderable on scroll-back).
- Assistant responses store `displayText` + `synthesisSeed` so paths can be
  regenerated deterministically (or cached as additional `stroke` rows).
- `render_progress` records how far the animation reached; on cold restart the
  response is rendered **fully drawn** at `charIndex/pathOffset` (no re-animation)
  to preserve the e-ink feel and avoid replay.
- `ConversationEngine` reconstructs history from `message` rows via `PageStore`.

### History windowing (ConversationEngine)

- A sliding window of the **last N turns** + a **running summary** of older turns
  is sent to the LLM. This caps tokens and avoids `context_length_exceeded`.
- N and the summary are observable (`historySize(): Int`). The trim strategy is
  an architectural property, not deferred.

---

## 8. Handwriting synthesis feasibility (key risk)

### 8.1 Stroke-font verification (must do before core work)

`Paint.getTextPath()` returns **closed outline contours** for standard TTFs —
tracing those animates "drawing the outline," not pen writing. Therefore the
chosen font **must be verified on-device** via `getTextPath()` to produce true
**open single-stroke paths**.

- Candidate single-stroke fonts (Hershey-derived / "single-line" engraving fonts)
  must be checked for **full Cyrillic coverage** and open paths.
- **Fallback if no suitable TTF exists:** programmatically generated stroke data
  per Cyrillic character (open handwriting datasets or centerline decomposition
  of glyph outlines). This guarantees the experience even without a perfect font.
- **Multi-stroke glyphs** (e.g. "Д", "Ж", "Й") are segmented by
  `PathMeasure.nextContour()` so the renderer inserts pen-lifts between
  disconnected sub-paths.

### 8.2 Glyph fallback granularity

- If a glyph is missing in the stroke font, fall back at **word (contiguous run)
  granularity** — never per-character — with a subtle visual cue (faint underline
  or alternate ink tone) so the inconsistency is signposted, not jarring.

### 8.3 Font licensing

- Bundled font must be **open-licensed (OFL/CC0/Apache)**; recorded in a
  `NOTICE`/third-party-licenses screen.

---

## 9. Security & Privacy

- **Credentials** (LLM API key, endpoint) in `EncryptedSharedPreferences` backed
  by the **Android Keystore**. Never plain `SharedPreferences`, Room, or logs.
- **Transport:** TLS 1.2+; `android:usesCleartextTraffic="false"`; HTTPS-only
  base URLs enforced on save.
- **Logging:** no headers, bodies, or keys logged. If an HTTP interceptor is
  used, it is **debug-build only** with `Authorization` redacted; redaction also
  covers crash logs and analytics.
- **Privacy disclosure (first launch + settings):** handwriting text and the AI
  response are sent to the configured third-party LLM provider; OCR is on-device.
  An opt-in acknowledgment is required before the first cloud request. A
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
| No network | SendOrchestrator / ConversationEngine | queue; "no connection — will send when back"; auto-retry on connectivity |
| LLM timeout (30s) / 5xx | LlmProvider | 2 retries, exp backoff (1s, 3s) |
| LLM 429 | LlmProvider | 1 retry after 30s; distinct UI ("you're writing fast") |
| LLM empty response | ConversationEngine | 1 auto-retry (different seed); else draw "…" ink + inline "tap to retry" |
| Cancellation | SendOrchestrator | cancel-window cancels OCR; LLM not cancelable from window; pen-during-LLM = follow-up turn |
| Missing glyph | HandwritingSynthesizer | word-granularity fallback + cue (§8.2) |
| Long response | HandwritingRenderer | progressive via `currentGlyphIndex` (§4.3); never block UI |
| Room write failure | PageStore | returns `Result`; transient -> retry + small snackbar; permanent -> diagnostics/export hint |
| Wrong-language input | InkRecognizer | Russian-only v1 (§6); low-confidence discard |
| **Hot backgrounding** | DrawSurface/Renderer | pause animation; resume from current position |
| **Cold restart mid-response** | PageStore + Renderer | restore from Room; render response fully-drawn at `render_progress` (no replay) |

---

## 11. Testing

### Unit (pure logic, no Android)
- **ConversationEngine** — history windowing + persona assembly, with a fake
  streaming `LlmProvider` (`Flow<LlmChunk>`). Core logic; dense coverage.
- **PauseDetector** — `observe` with `FakeMonotonicClock`; assert timings.
- **SendOrchestrator** — state-machine transitions; cancel-window cancellation;
  conflated-queue behavior.
- **HandwritingSynthesizer** (stroke-font impl) — path counts, empty text,
  glyph fallback (word granularity), seeded-jitter bounds.
- **PageLayout** — line-break/wrap and cursor advancement given fake layoutables.
- **HandwritingRenderer** — pure progress test: given a path of length L and
  `progress=0.5`, emits frame at offset 0.5L (regression guard beyond tracer).
- **LlmProvider** (real impl) — MockWebServer against streamed OpenAI protocol.
- **PageStore** — Room in-memory (Robolectric).

### UI / Compose
- Inject pointer events into `DrawSurface`; assert rendering + state transitions.

### Integration — tracer bullet (vertical slice)
- Minimal end-to-end: write a word -> canned **streamed** response drawn as
  strokes. Validates the pipeline and the **feel on real e-ink** early.

### Manual (human judgment)
- The "magic" — animation speed, e-ink refresh, tactile feel — is subjective;
  verified by hand on the actual BOOX. Explicit human checkpoint.

---

## 12. Open Questions (non-blocking; resolved at implementation)

1. **Specific LLM provider** — behind `LlmProvider`; chosen at implementation.
2. **Specific Cyrillic single-stroke font** — selected + verified open-path at
   implementation (§8.1); generated-stroke fallback if none.
3. The **values** of pause/cancel-window/confidence floors are **tunable on
   device**, but the **contracts** (that they exist and are configurable) are
   fixed in §4.1.

---

## 13. Decisions Log

- **Approach:** Native Android over PWA/companion pipeline — seamless magic; BOOX
  is stock Android.
- **OCR:** ML Kit Digital Ink (on-device, Russian).
- **LLM:** **streaming** abstraction now, concrete provider later.
- **Handwriting:** stroke fonts + jitter now (verified open-path + fallback),
  ML synthesis later via the `HandwritingSynthesizer` seam.
- **Persona:** configurable; default = friendly "magic diary companion".
- **Send trigger:** auto-send on pause (1500 ms) + cancel-window (800 ms).
- **Async shape:** `suspend`/`Flow` end-to-end; one canvas owner; PageStore as
  single source of truth; explicit SendOrchestrator state machine.
- **Russian-only v1** for OCR.
