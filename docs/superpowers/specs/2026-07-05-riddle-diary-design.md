# Riddle Diary — Handwriting Chat for BOOX Note Air5 C

- **Status:** Design v7 (post-review round 6) — pending implementation plan
- **Date:** 2026-07-05
- **Proof level:** inspected
- **Target device:** BOOX Note Air5 C
- **Review:** 3-subagent review over 6 rounds. deepseek + minimax at LGTM; kimi
  round-6 must-fix = M13 (message-creation ownership) + M14 (idempotent append).
  v7 closes those, plus the cancelled-message-resend bug, plus non-blocking
  minors.

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
- **Eraser tool** — eraser tool-type input is ignored (no erase gesture in v1).
  (Finger input is **not** ink either — it passes through to scroll; see §3.2.)
- **Handwritten correction** of OCR — the inline "edit" affordance accepts typed
  text only in v1.
- Platforms other than this Android device.
- Non-Russian handwriting input (see §6 — Russian-only v1, explicit).

---

## 3. Architecture

### 3.1 Core loop (streaming end-to-end)

```
                StrokeEvent (Down/Move/Up + t, pressure, tilt)
 STYLUS ──────────────────────► DrawSurface (single canvas owner)
                               (stylus => ink; finger passes through to scroll;
                                eraser/barrel ignored)
                                   │ pointerEvents() (multicast)
                     ┌─────────────┼──────────────────────────────────┐
                     │ (render user ink)                              │
                     ▼                                               ▼
                PageLayout ──placed paths──► HandwritingRenderer   SendOrchestrator
                (maps glyph-local to              ▲                (owns message creation:
                 screen-space; committed          │                 on first Down ->
                 lines frozen)                    │ placed           createMessage ->
                     ▲                            │ paths (SUSPEND)  activeMessageId;
                     │ glyph-local paths                                confirm/edit seam;
                     │                                                   state machine)
                HandwritingSynthesizer ◄──word chunks──► ConversationEngine ◄── LlmChunk Flow ── LlmProvider
                                                                  ▲                       (pure network seam;
                   PageStore ◄── text+seed ────────────────────────┘                        typed errors;
                 (source of truth;                                                      injected via
                  append-only,                                                           LlmProviderFactory)
                  idempotent)                                                  ▲
                     ▲                                                        │ request (messages incl. system msg)
                     │ StrokeBatch (full block) + preContext                   │
                SendOrchestrator ──recognize(StrokeBatch)──► InkRecognizer ───result──► (passed to respond(messageId, result))
                     ▲
                     │ SendTrigger
                SendOrchestrator ◄── PauseDetector(clock) ◄── StrokeEvent
                caption StateFlow ──► DrawSurface (caption)
                (collected under repeatOnLifecycle(STARTED))
```

Key properties:
- **Streaming everywhere:** OCR `suspend`, LLM `Flow<LlmChunk>`, synthesis
  `Flow<List<GlyphPath>>`. The renderer begins glyph N while the model still
  emits N+1. **Backpressure is explicit:** the **synth -> renderer edge uses
  `BufferOverflow.SUSPEND`** with `extraBufferCapacity = V` (every glyph rendered
  once; lookahead bounded by V); the `SendOrchestrator` wires the `buffer(...)`
  operator on the consumer side (it owns the per-message `CoroutineScope`); LLM
  edge `.SUSPEND`.
- **`SendOrchestrator` owns message creation:** on the first `Down` of a block it
  calls `PageStore.createMessage(conversationId)` and publishes
  `activeMessageId`; `DrawSurface` appends per-`Up` stroke batches using that id.
  No race, single owner.
- **Persistence throughout the loop** (append-only): raw strokes, recognized
  text, and assistant text + synthesis seed are persisted via `PageStore` as
  produced. Raw strokes **batched per pointer `Up`** with a caller-generated
  **idempotency token**. `append` is **idempotent** — a `UNIQUE` violation on a
  repeated token is treated as success (content verified), so a retry after a
  lost ack neither duplicates nor fails. Raw user strokes flow
  `DrawSurface -> PageStore`; **synthesized paths are NOT persisted as ink** —
  regenerated from `synthesisSeed`; `PageStore` receives only `AssistantChunk`
  (text + seed) from `ConversationEngine`.
- **`PageStore` is the single source of truth**; `ConversationEngine` reads and
  writes through it and holds no exclusive message state.
- **One canvas owner (`DrawSurface`)**; `HandwritingRenderer` is a `DrawScope`
  primitive it invokes.
- **Caption + preContext edges:** recognized text is emitted to `DrawSurface` as
  a caption; preceding text is read from `PageStore` to pass as `preContext`.
- **Lifecycle:** the orchestrator's collectors and the animation driver run under
  `repeatOnLifecycle(Lifecycle.State.STARTED)`; pause on `STOPPED`.

### 3.2 Modules

All I/O-crossing interfaces are `suspend` or `Flow`. Time is injected. Pinned
constants live in `RiddleConfig` (§14).

| Module | Responsibility | Draft interface |
|---|---|---|
| **DrawSurface** | Sole canvas owner + composable entry point. `TOOL_TYPE_STYLUS` produces ink; **finger events pass through** to the containing scrollable (`PointerEventPass`/`pointerInteropFilter`); eraser/barrel ignored. Renders user ink + AI ink; batches strokes per `Up` and appends them to the orchestrator-provided `activeMessageId` (with idempotency token). | `pointerEvents(): Flow<StrokeEvent>`; `pageState: StateFlow<PageSnapshot>` |
| **PauseDetector** | Pure logic: detects writing pause from **raw pointer events**; injectable clock. | `observe(events: Flow<StrokeEvent>, clock: MonotonicClock): Flow<SendTrigger>` |
| **SendOrchestrator** | **Owns message creation** (`createMessage` on first `Down` -> `activeMessageId`) and the send state machine (incl. `AwaitingConfirmation`); drives recognition (`InkRecognizer`) and **conditionally launches** `ConversationEngine` only on auto-send or after ✓/edit; **confirm/edit seam**; cancels in-flight work; single worker on `Dispatchers.Default`; wires synth-edge backpressure; collected under `repeatOnLifecycle(STARTED)`. | `fun process(triggers: Flow<SendTrigger>)`; `fun confirm(messageId: Long, editedText: String?)`; `val sendState: Flow<SendState>`; `val caption: StateFlow<String?>`; `val activeMessageId: StateFlow<Long?>` |
| **InkRecognizer** | OCR of a **full writing block** -> text + confidence + alternatives. `preContext` = prior message text for vocabulary priming (null on the first message of a conversation). | `suspend fun recognize(batch: StrokeBatch, preContext: String?): RecognizerResult` |
| **RecognitionModelManager** | ML Kit Russian model lifecycle. | `modelState: Flow<ModelState>`; `suspend fun ensureModel()` |
| **ConversationEngine** | History windowing; injects the persona system message as the first `Message`; **gated on `privacyAcknowledged`**; **normalizes streamed `LlmChunk.Text` into word-boundary chunks**; persists via PageStore; maps `LlmErrorCategory -> MessageStatus`/UI. (`LlmProvider` is **injected** — ConversationEngine does not construct it.) | `suspend fun respond(messageId: Long, input: RecognizerResult): Flow<ConversationUpdate>` |
| **Summarizer** | Running summary of older turns when the window slides (pluggable; v1 may reuse `LlmProvider`). | `suspend fun summarize(older: List<Message>): String` |
| **LlmProviderFactory** | Constructs `LlmProvider` from `LlmEndpoint` + `RiddleConfig` + credentials (read from `EncryptedSharedPreferences`) — the single place that touches Android security plumbing. | `fun create(endpoint: LlmEndpoint, config: RiddleConfig): LlmProvider` |
| **LlmProvider** | Pluggable **streaming** chat seam (OpenAI-compatible now, local later). **Pure network seam** — decoupled from persona and settings. Owns its own retries and emits **typed** `LlmChunk.Error(category, retryable)`. | `suspend fun chat(messages: List<Message>): Flow<LlmChunk>` |
| **HandwritingSynthesizer** | Text -> **glyph-local** stroke paths (stroke-font + jitter now, ML later); streaming word-boundary chunks. (Does NOT place on screen — that is `PageLayout`'s job.) | `fun synthesize(text: Flow<String>, seed: SynthesisSeed): Flow<List<GlyphPath>>`; plus `synthesizeAll(text, seed): List<GlyphPath>` for replay/tests. |
| **PageLayout** | Compositor: maps glyph-local paths to screen-space `PlacedStrokePath`; line-breaking; cursor; vertical flow. **Streaming-safe:** completed lines are committed (frozen); only the currently-building line is mutable. | `fun layout(content: List<Layoutable>, viewport: Rect, cursor: Cursor): List<PlacedStrokePath>` |
| **HandwritingRenderer** | `DrawScope` primitive; animates placed paths via `PathMeasure` + `nextContour()` pen-lifts; throttled to e-ink cadence. | `fun DrawScope.renderAnimated(paths: List<PlacedStrokePath>, progress: State<Float>)` |
| **EInkDisplayAdapter** | E-ink refresh seam: partial refresh (region) during animation, full refresh after each response; paired begin/end. | `fun beginPartialRefresh(region: Rect?)`; `fun endPartialRefresh()`; `suspend fun fullRefresh()` |
| **PageStore** | Single source of truth; Room-backed, append-only; **one internal retry**; `append` is **idempotent** (a `UNIQUE` violation on a repeated `idempotencyToken` = success). Message creation is a dedicated seam (returns the id). | `suspend fun createMessage(conversationId: Long): Result<Long>`; `suspend fun append(event: PageEvent): Result<Unit>`; `fun observe(): Flow<PageSnapshot>` |
| **PersonaRepository** | Persona CRUD + validation (**Room-backed**; personas are not secrets). Exactly one active (transactional toggle + partial unique index on `isActive=1`). | `personas: Flow<List<Persona>>`; `suspend fun active(): Persona`; `suspend fun upsert(p: Persona)` |

**Boundary principle:** each module is deep and replaceable. Explicit seams:
`LlmProviderFactory`, `LlmProvider`, `HandwritingSynthesizer`, `Summarizer`,
`EInkDisplayAdapter`, `RecognitionModelManager`, `MonotonicClock` — each has a v1
impl and a fake/test impl. `HandwritingSynthesizer` emits glyph-local paths;
`PageLayout` is the only module that knows screen coordinates.

### 3.3 Send-state machine (owned by SendOrchestrator)

```
                           (pen within cancel-window)
            ┌─────────────────────────────────────────────┐
            ▼                                             │
idle ─(Up+pause)─► paused ─cancel-window─► recognizing ──┘
  ▲                                            │
  │                            (conf>=0.7)     │ (0.5<=conf<0.7)
  │                                  ▼         ▼
  │                              sending   awaitingConfirmation ◄─┐
  │                                  │       │      │             │ (more strokes:
  │                  (first LlmChunk)│       │(✓/edit)│(cancel)   │  re-run OCR on
  │                                  ▼       └──┬────┘           │  augmented batch;
  └───────────────────────────── complete ◄── streaming           │  caption updates)
                                             ▲
                                             │ (new strokes during streaming/animating
                                             │  => follow-up turn: a NEW message is
                                             │  created; current response keeps streaming;
                                             │  new block queues via conflation until
                                             │  in-flight completes)
```

- The **cancel-window applies only to `paused`/`recognizing`** (pre-network).
  Cancel marks the message `status=CANCELLED` (never re-flushed) and returns to
  `idle`; a new block starts a fresh message.
- **Confidence 0.5–0.7** -> `AwaitingConfirmation`: caption + "edit" (typed
  text) + "✓". `ConversationEngine.respond` is **NOT** launched until ✓/edit.
  **Strokes during `AwaitingConfirmation`** extend the current block; the caption
  updates and OCR is re-run on the augmented batch before `confirm()` commits.
- Once the **first `LlmChunk` arrives**, the request is no longer cancelable; new
  strokes during streaming/animating start a **follow-up turn** (new message).
- Only **one** LLM request in-flight; triggers arriving while busy are conflated.
- `complete -> idle` returns the orchestrator to accept the next turn.

### 3.4 Stroke -> message lifecycle

- A **writing block** = strokes from the first `Down` after `idle` until the
  block ends. Block ends when: the message is sent (auto or ✓), or cancelled, or a
  follow-up turn begins.
- **`SendOrchestrator` owns message creation.** On the first `Down` of a block it
  calls `PageStore.createMessage(conversationId)` -> `messageId` and publishes it
  via `activeMessageId`; `DrawSurface` appends per-`Up` stroke batches
  `append(UserStrokes(messageId, …, token))` using that id.
  **`SendTrigger.requestId` *is* that `messageId`** (no separate correlation).
- `SendOrchestrator` aggregates the block's strokes (via `PageStore.observe()`)
  into **one `StrokeBatch`** before calling `InkRecognizer.recognize`.
- **`PENDING` means "not yet sent to the LLM".** OCR may populate
  `recognizedText` while still `PENDING` (during `AwaitingConfirmation` or while
  privacy is declined / credentials missing). Status moves to `RECOGNIZED` when
  recognized text is committed for sending; `SENDING -> STREAMING -> COMPLETE`
  (or `ERROR`, or `CANCELLED`). **Cancelled rows are never re-flushed.** Flush of
  queued messages — triggers: **privacy re-acknowledge**, **connectivity
  restore**, **credentials configured** — queries
  `status=PENDING AND recognizedText IS NOT NULL`.

---

## 4. UX & Data Flow

### 4.1 Send trigger — auto-send on writing pause

- Pause threshold **1500 ms** (v1; tunable).
- **Cancel-window = 800 ms** (v1; tunable 400–1500 ms). Faint "recognizing…"
  indicator; any pen-down within the window cancels and resets.
- Empty/scribble input (confidence < **0.5**) is not sent; a brief fading "…"
  marker acknowledges the drop.
- **Confidence dual-path:** `>= 0.7` -> auto-send after a brief display-only
  window; `0.5 <= confidence < 0.7` -> `AwaitingConfirmation` (edit = **typed
  text** in v1); `< 0.5` -> discard.

### 4.2 Concurrency — writing during AI response rendering

- New strokes during AI rendering start a **new writing block below** the
  rendering response (follow-up turn); the AI animation continues independently.
- Strokes during the cancel-window cancel the pending send; strokes during
  streaming/animating contribute to the **next** turn.
- Only one LLM request in-flight; subsequent triggers are **conflated**.

### 4.3 Navigation — one continuous notebook

- Continuous vertical scroll (driven by finger/pointer pass-through, §3.2). User
  writes near the bottom; AI replies below; older entries scroll upward.
- **Auto-scroll during rendering** keeps the rendering ink near the top; a manual
  scroll gesture during rendering cancels auto-scroll for that response.
- **Scroll during an in-flight animation:** already-drawn lines remain visible;
  the animation continues at its own offset; releasing scroll near the active ink
  resumes auto-scroll for that response.
- **Long responses:** the renderer maintains `currentGlyphIndex`; synthesis
  produces up to **`V = 64` glyphs** ahead; if the user scrolls past, the index
  fast-forwards (past glyphs are NOT re-animated). Only `V` glyph-paths in memory.
- **Scroll-back** reconstructs completed messages from raw `stroke` (user) and
  regenerates assistant paths from `synthesisSeed`. v1 does not cache placed
  paths (acceptable for hundreds of messages; bounded LRU is a future option).

### 4.4 The "ink appears" animation (core magic)

- `HandwritingRenderer` draws glyph-by-glyph via `PathMeasure`; multi-stroke
  glyphs use `PathMeasure.nextContour()` for pen-lifts.
- Subtle ink-spread (opacity pulse) per glyph.
- **E-ink tuning:** redraws throttled to panel-refresh cadence (<= ~1 Hz on
  Kaleido 3); a **full refresh** runs after each response (`EInkDisplayAdapter`).

### 4.5 OCR confirmation — no modal

- Recognized text is a subtle caption under the user's strokes; tap to edit
  (typed text).
- Mechanism: `SendOrchestrator` emits recognized text to a caption `StateFlow`
  observed by `DrawSurface` **always**. `ConversationEngine.respond` is launched
  **only** on the auto-send path (confidence >= 0.7) **or** after the user taps
  ✓ / edits in `AwaitingConfirmation` — never before. So unconfirmed text is not
  sent to the cloud.

### 4.6 First-launch flow

1. **ML Kit Russian model download** (splash with progress); if offline, persist
   the requirement and persist captured strokes to Room regardless — never drop
   user input; offer "Recognize pending strokes now" once available.
2. **Privacy acknowledgment:** `privacyAcknowledged` gates `ConversationEngine`
   (and thus any cloud send). If the user **declines**, messages stay
   `status=PENDING` (with `recognizedText`) and a permanent "configure provider /
   enable cloud" prompt is shown; nothing is silently sent. Flush triggers:
   privacy re-acknowledge, connectivity restore, **credentials configured**.
3. **LLM provider setup:** HTTPS endpoint, model name, API key, optional
   temperature/maxTokens. "Credentials not configured" is detected **distinctly
   from network errors** (not surfaced as 401). Credentials in
   `EncryptedSharedPreferences` (Keystore).
4. Optional persona selection (default provided).

---

## 5. Tech Stack

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin (coroutines + Flow) | streaming end-to-end; explicit backpressure |
| UI | Jetpack Compose | single `Canvas` in `DrawSurface`; `Animatable`/`PathMeasure`; `repeatOnLifecycle`; pointer pass-through |
| `minSdk` / `targetSdk` | 31 / 35 | device is API 35 |
| Input OCR | ML Kit **Digital Ink Recognition** | Russian model, on-device; `suspend`, typically **0.5–2 s** (assumption — verify on device) |
| LLM client | OkHttp + kotlinx.serialization, OpenAI-compatible, **SSE/chunked** | behind `LlmProvider` (via `LlmProviderFactory`); defaults via `RiddleConfig`; typed errors via `LlmErrorCategory` |
| Persistence | Room (SQLite) | schema §7 |
| Handwriting (v1) | single-line **stroke** TTF, **verified open-path** + jitter; generated-stroke fallback | §8 |
| Stylus | `PointerInput`/`MotionEvent` (EMR) | pressure, tilt; `TOOL_TYPE_STYLUS` => ink; finger passes through; eraser/barrel ignored |
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
            -- exactly one isActive=true:
            --   CREATE UNIQUE INDEX persona_active_unique ON persona(isActive) WHERE isActive = 1
            --   (plus a transactional toggle in PersonaRepository)
            -- stored in Room; NOT a secret.
message(id PK, conversationId FK, role, displayText, recognizedText?,
        synthesisSeed?, status, createdAt, sortIndex)
            -- status: pending | recognized | sending | streaming | error | complete | cancelled
            -- @Index(value=["conversationId","sortIndex"]) for history reads
stroke(id PK, messageId FK, strokeIndex, isUser, pointsJson, idempotencyToken UNIQUE)
            -- raw points for replay; idempotencyToken makes per-Up appends safe to retry
render_progress(messageId PK, glyphIndex, pathOffset)   -- 1:1 with assistant msg
conversation_summary(id PK, conversationId FK, upToMessageId, summary)  -- running summary
```

- Raw user strokes live in `stroke` (re-renderable on scroll-back).
- Assistant responses store `displayText` + `synthesisSeed`; paths regenerated
  deterministically (not persisted as ink).
- `render_progress`: on cold restart, glyphs with index < `glyphIndex` are shown
  fully drawn; the glyph at `glyphIndex` resumes from `pathOffset`; later glyphs
  are not yet drawn. No animation replay.
- `status` makes the pending/sending/error/retry lifecycle queryable;
  `CANCELLED` rows are excluded from flush.

### History windowing (ConversationEngine)

- Sliding window of the **last N turns** (`RiddleConfig.historyWindowTurns`) + a
  **running summary** of older turns sent to the LLM. Summary produced by the
  `Summarizer` seam, persisted in `conversation_summary` (advanced as the window
  slides; summarize only on slide to bound cost). N and the summary are
  observable (`historySize(): Int`).

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
  chunks** (word-splitting owned by `ConversationEngine`, which normalizes
  streamed `LlmChunk.Text`).

### 8.3 Font licensing

- Bundled font must be **open-licensed (OFL/CC0/Apache)**; recorded in a
  `NOTICE`/third-party-licenses screen.

---

## 9. Security & Privacy

- **Credentials** (LLM API key, endpoint) in `EncryptedSharedPreferences` backed
  by the **Android Keystore** (read only by `LlmProviderFactory`). Never plain
  prefs, Room, or logs. (Personas are not secrets and live in Room.)
- **Transport:** TLS 1.2+; `android:usesCleartextTraffic="false"`; HTTPS-only
  base URLs enforced on save.
- **Logging:** no headers/bodies/keys logged. Any HTTP interceptor is
  **debug-build only** with `Authorization` redacted; redaction covers crash logs
  and analytics.
- **Privacy acknowledgment:** `privacyAcknowledged` gates `ConversationEngine`
  (§4.6). Handwriting text and the AI response go to the configured third-party
  LLM; OCR is on-device. A **local-only** provider path is possible (future
  `LlmProvider` impl) and the UI must not assume cloud.

---

## 10. Error Handling & Edge Cases (owners assigned)

`LlmProvider` classifies failures into `LlmErrorCategory` and owns its own
retries; `ConversationEngine` maps the (post-retry) category to `MessageStatus`
and UI.

| Case | Owner | Policy |
|---|---|---|
| Auto-send false trigger | SendOrchestrator | cancel-window (§4.1); cancel marks `status=CANCELLED` (never re-flushed) |
| Rapid repeated triggers | SendOrchestrator | conflated channel; one in-flight request |
| OCR model not downloaded | RecognitionModelManager | download screen; persist strokes meanwhile; "Recognize pending" |
| OCR low confidence | InkRecognizer + UI | `<0.5` discard ("…"); `0.5–0.7` `AwaitingConfirmation`; `>=0.7` auto-send |
| OCR hard failure | InkRecognizer | "couldn't read the handwriting" + retry |
| No network (`Network`) | SendOrchestrator / ConversationEngine | queue (`status=PENDING`); auto-retry on connectivity |
| Credentials missing (`CredentialsMissing`) | UI + SendOrchestrator | distinct from network; permanent "configure provider" prompt; messages stay `PENDING`; flush when configured |
| Privacy not acknowledged | ConversationEngine | `respond` gated; flush on re-acknowledge |
| LLM timeout / 5xx (`Timeout`/`ProviderError`) | LlmProvider | 2 retries, exp backoff (1s, 3s); then `LlmChunk.Error(retryable=false)` |
| LLM 429 (`RateLimited`) | LlmProvider | 1 retry after 30s; distinct UI |
| Auth (`Auth`) | UI | re-prompt credentials; do not retry silently |
| LLM **partial failure** (text drawn then socket died) | ConversationEngine | preserve drawn glyphs; append "…" + retry — **no full regen** |
| LLM **empty** response | ConversationEngine | 1 LLM auto-retry (new request); if text then arrives, regenerate paths with a fresh `SynthesisSeed`; else "…" + "tap to retry" |
| LLM **post-retry failure** | ConversationEngine | `status=ERROR`; "tap to retry" UI |
| Cancellation | SendOrchestrator | cancel-window cancels OCR (pre-network only); pen-during-LLM = follow-up turn |
| Missing glyph | HandwritingSynthesizer | word-granularity fallback + alternate ink tone (§8.2) |
| Long response | HandwritingRenderer | progressive via `currentGlyphIndex` (§4.3); never block UI |
| Room write failure | PageStore | `append`/`createMessage` do **one internal retry**; transient = `SQLiteFullException`/disk I/O -> snackbar; permanent = constraint/schema mismatch -> diagnostics hint; a repeated `idempotencyToken` (UNIQUE) is **idempotent success**, not a failure |
| Wrong-language input | InkRecognizer | Russian-only v1 (§6); low-confidence discard |
| **Hot backgrounding** | SendOrchestrator (lifecycle owner) | pause animation (`STOPPED`); resume from current position |
| **Cold restart mid-response** | SendOrchestrator (restore) via PageStore+Renderer | restore from Room; render response at `render_progress` (no replay) |

---

## 11. Testing

### Unit (pure logic, no Android)
- **ConversationEngine** — history windowing + system-message injection; word
  normalization; `LlmErrorCategory -> MessageStatus` mapping; `privacyAcknowledged`
  gating; fake streaming `LlmProvider`. Dense coverage.
- **Summarizer** (fake) — invoked on window slide; summary persisted.
- **PauseDetector** — `observe` with `FakeMonotonicClock`.
- **SendOrchestrator** — message creation on first `Down` + `activeMessageId`;
  state transitions incl. `AwaitingConfirmation` (re-OCR on augmented strokes) and
  `complete->idle`; cancel only from `paused`/`recognizing` -> `CANCELLED`;
  conflation; conditional launch (engine NOT launched in `AwaitingConfirmation`);
  `confirm(messageId, editedText)`; StrokeBatch aggregation; follow-up turn.
- **HandwritingSynthesizer** (stroke-font) — path counts; empty text;
  word-granularity fallback; seeded-jitter bounds; **determinism** (same seed +
  same text => byte-equal paths); emits glyph-local paths (no screen coords).
- **PageLayout** — line-break/wrap; glyph-local -> placed mapping; committed
  lines frozen during reflow.
- **HandwritingRenderer** — pure progress: path length L, `progress=0.5` =>
  offset 0.5L.
- **LlmProvider** (real) — MockWebServer, streamed OpenAI protocol; typed
  `LlmErrorCategory` mapping; retry policy.
- **PageStore** — Room in-memory; `createMessage` returns id; `append`
  idempotency (repeated token => success, no dup); single retry; transient vs
  permanent; `CANCELLED` excluded from flush.

### UI / Compose
- Inject pointer events into `DrawSurface`; assert stylus => ink; **finger passes
  through** (scroll container receives it); eraser/barrel ignored.

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
- **OCR:** ML Kit Digital Ink (on-device, Russian); recognizes a full writing
  block (`StrokeBatch`), not per-stroke.
- **LLM:** **streaming** abstraction; **pure network seam** (decoupled from
  persona and settings; constructed by `LlmProviderFactory`); typed errors
  (`LlmErrorCategory`); privacy gating in `ConversationEngine`.
- **Handwriting:** stroke fonts + jitter now (verified open-path + fallback), ML
  later via `HandwritingSynthesizer`; **synth emits glyph-local paths**;
  `PageLayout` is the only screen-coordinate owner; synth->renderer `SUSPEND`.
- **Persona:** configurable; `systemPrompt` **snapshotted per conversation at the
  first user message**; exactly one active.
- **Send trigger:** auto-send on pause (1500 ms) + cancel-window (800 ms; pre-
  network only) + `AwaitingConfirmation` for 0.5–0.7 (edit = typed text); engine
  launched **only** on auto-send or ✓.
- **Async shape:** `suspend`/`Flow` end-to-end with explicit backpressure; one
  canvas owner; **finger events pass through to scroll**; **`SendOrchestrator`
  owns message creation**; PageStore single source of truth (`createMessage` +
  append-only **idempotent** with one internal retry); explicit SendOrchestrator
  state machine; `repeatOnLifecycle(STARTED)`.
- **Russian-only v1**; stylus-ink-only v1 (finger = scroll; eraser/barrel ignored).

---

## 14. Appendix — Data Model & Config (boundary contracts)

```kotlin
// ---- Input / ink ----
data class StrokePoint(val x: Float, val y: Float, val tMs: Long,
                       val pressure: Float, val tilt: Float)
data class Stroke(val id: Long, val points: List<StrokePoint>)
data class StrokeBatch(val strokes: List<Stroke>)   // the FULL writing block of one message
data class IdempotencyToken(val value: String)      // per-Up dedup key

sealed interface StrokeEvent {            // raw pointer stream from DrawSurface
    data class Down(val p: StrokePoint) : StrokeEvent
    data class Move(val p: StrokePoint) : StrokeEvent
    data class Up(val p: StrokePoint) : StrokeEvent
}

// ---- Recognition ----
data class RecognizerResult(val text: String, val confidence: Float,
                            val alternatives: List<String> = emptyList())  // alternatives: future alt-text quick-pick
sealed interface ModelState { object NotDownloaded : ModelState; object Downloading : ModelState; object Ready : ModelState; data class Error(val message: String) : ModelState }

// ---- LLM streaming ----
enum class Role { SYSTEM, USER, ASSISTANT }
data class Message(val id: Long, val conversationId: Long, val role: Role,
                   val displayText: String, val recognizedText: String? = null,
                   val synthesisSeed: SynthesisSeed? = null,
                   val status: MessageStatus = MessageStatus.PENDING,
                   val createdAt: Long, val sortIndex: Int)
enum class MessageStatus { PENDING, RECOGNIZED, SENDING, STREAMING, ERROR, COMPLETE, CANCELLED }
// PENDING = not yet sent to the LLM (recognizedText may already be set).
// CANCELLED = user cancelled; excluded from flush; never sent.

enum class LlmErrorCategory { Network, Timeout, RateLimited, Auth, CredentialsMissing, ProviderError }

sealed interface LlmChunk {
    data class Text(val text: String) : LlmChunk
    object Done : LlmChunk
    data class Error(val category: LlmErrorCategory, val message: String, val retryable: Boolean) : LlmChunk
}

data class LlmEndpoint(val baseUrl: String, val modelId: String)   // HTTPS only

sealed interface ConversationUpdate {     // ConversationEngine.respond(messageId, …) emissions
    data class Recognized(val messageId: Long, val result: RecognizerResult) : ConversationUpdate
    data class AssistantChunk(val messageId: Long, val text: String, val seed: SynthesisSeed) : ConversationUpdate
    data class StatusChange(val messageId: Long, val status: MessageStatus) : ConversationUpdate
}

// ---- Handwriting / layout ----
data class SynthesisSeed(val seed: Long, val fontId: String, val jitter: Float)
data class GlyphPath(val path: android.graphics.Path, val glyphIndex: Int)    // glyph-local
data class PlacedStrokePath(val path: android.graphics.Path,
                            val origin: Pair<Float, Float>, val glyphIndex: Int)  // screen-space
data class Cursor(val x: Float, val y: Float)
sealed interface Layoutable {
    data class UserInk(val strokes: List<Stroke>) : Layoutable
    data class AssistantText(val text: String, val seed: SynthesisSeed) : Layoutable
}

// ---- Orchestration ----
data class SendTrigger(val requestId: Long, val tMs: Long)  // requestId == messageId of the block
sealed interface SendState {
    object Idle : SendState
    object Paused : SendState
    object Recognizing : SendState
    object AwaitingConfirmation : SendState
    object Sending : SendState
    object Streaming : SendState
    object Complete : SendState
    object Cancelled : SendState
    data class Error(val category: LlmErrorCategory, val reason: String) : SendState
}

// ---- Persistence events / snapshot ----
sealed interface PageEvent {              // PageStore append-only increments (message already created)
    data class UserStrokes(val messageId: Long, val strokes: List<Stroke>, val token: IdempotencyToken) : PageEvent
    data class Recognized(val messageId: Long, val result: RecognizerResult) : PageEvent
    data class AssistantChunk(val messageId: Long, val text: String, val seed: SynthesisSeed) : PageEvent
    data class RenderProgress(val messageId: Long, val glyphIndex: Int, val pathOffset: Float) : PageEvent
    data class StatusChange(val messageId: Long, val status: MessageStatus) : PageEvent
}
data class PageSnapshot(val messages: List<Message>, val pendingStrokes: List<Stroke>)
// pendingStrokes = strokes of the in-progress block whose message is not yet COMPLETE/ERROR/CANCELLED

// ---- Testability seams ----
interface MonotonicClock { fun nowMs(): Long }
class FakeMonotonicClock : MonotonicClock { var current = 0L; override fun nowMs() = current }

// ---- Tuning (single source of pinned constants) ----
data class RiddleConfig(
    val pauseMs: Long = 1500L,
    val cancelWindowMs: Long = 800L,
    val discardConfidence: Float = 0.5f,
    val confirmConfidence: Float = 0.7f,
    val synthLookaheadGlyphs: Int = 64,      // V
    val historyWindowTurns: Int = 20,        // N
    val llmTimeoutMs: Long = 30_000L,
    val llmRetry5xx: Int = 2,
    val llmBackoffMs: List<Long> = listOf(1_000L, 3_000L),  // len validated == llmRetry5xx at startup
    val llmRetry429DelayMs: Long = 30_000L,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
)
```
