# Riddle Diary — Handwriting Chat for BOOX Note Air5 C

- **Status:** Design v10 (post-review round 9) — **all 3 reviewers LGTM** — pending implementation plan
- **Date:** 2026-07-05
- **Proof level:** inspected
- **Target device:** BOOX Note Air5 C
- **Review:** 3-subagent review over 9 rounds → LGTM from deepseek, kimi, minimax.
  v8 made `DrawSurface` pure-render and `SendOrchestrator` the pipeline owner;
  v9 adopted two-messages-per-turn + per-response cumulative glyphIndex; v10 added
  `role` to `createMessage` (kimi M5). Remaining reviewer notes are non-blocking
  and deferred to the implementation plan.

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
 STYLUS ──────────────────────► DrawSurface (single canvas owner; PURE RENDER)
                               (stylus => ink; finger passes through to scroll;
                                eraser/barrel ignored; emits pointerEvents())
                                   │ pointerEvents()  (single source)
                     ┌─────────────┼──────────────────────────────────┐
                     │ (render from pageState)                        │
                     ▼                                               ▼
                PageLayout ──placed paths──► HandwritingRenderer   SendOrchestrator
                (maps glyph-local to              ▲                (OWNS THE PIPELINE:
                 screen-space; committed          │                 consumes pointerEvents;
                 lines frozen)                    │ placed           runs PauseDetector;
                     ▲                            │ paths (SUSPEND)  on first Down ->
                     │ glyph-local paths                             createMessage ->
                     │                                               activeMessageId;
                HandwritingSynthesizer ◄─word chunks─► ConversationEngine   serializes per-Up
                                                                  ▲          stroke appends AFTER
                   PageStore ◄── text+seed(chunkIndex) ───────────┘          createMessage (no race);
                 (source of truth;                                                 drives InkRecognizer;
                  append-only,                                                     conditionally launches
                  idempotent:                                                      ConversationEngine;
                  UserStrokes by token;                                            confirm/edit seam;
                  AssistantChunk by chunkIndex)                                    backpressure wiring)
                                                                  ▲
                                                                  │ request (List<LlmMessage>)
                                                          LlmProvider (pure network seam;
                                                                       typed errors; injected
                                                                       via LlmProviderFactory)
                SendOrchestrator ──recognize(StrokeBatch)──► InkRecognizer ──result──► (passed to respond(messageId, result))
                     ▲
                     │ SendTrigger (requestId == messageId)
                SendOrchestrator (internally) ◄── PauseDetector(clock) ◄── pointerEvents()
                caption StateFlow ──► DrawSurface (caption)
                (collected under repeatOnLifecycle(STARTED))
```

Key properties:
- **Streaming everywhere:** OCR `suspend`, LLM `Flow<LlmChunk>`, synthesis
  `Flow<List<GlyphPath>>`. The renderer begins glyph N while the model still
  emits N+1. **Backpressure is explicit:** the **synth -> renderer edge uses
  `BufferOverflow.SUSPEND`** with `extraBufferCapacity = V` (every glyph rendered
  once; lookahead bounded by V); `SendOrchestrator` wires the `buffer(...)`
  operator on the consumer side; LLM edge `.SUSPEND`.
- **`SendOrchestrator` owns the stroke->message pipeline:** it consumes the raw
  `pointerEvents()` stream (and internally runs `PauseDetector`). On the first
  `Down` of a block it calls `PageStore.createMessage(conversationId, Role.USER)`
  -> `messageId`, then **serializes per-`Up` stroke `append`s after creation** in
  its own coroutine — a fast tap whose `Up` precedes the async `createMessage`
  completion is **buffered**, never dropped or mis-attached. If `createMessage`
  fails permanently, a "couldn't start a new message; tap to retry" UI is shown
  and further pen input for that block is ignored. **`DrawSurface` is pure
  rendering and never writes to `PageStore`.** Single owner, no race.
- **Persistence throughout the loop** (append-only, idempotent): raw strokes
  (per `Up`, with `idempotencyToken`), recognized text, and assistant text +
  synthesis seed (per chunk, with `chunkIndex`) are persisted via `PageStore`.
  `append` is **idempotent** — a duplicate `UserStrokes` token OR a duplicate
  `(messageId, chunkIndex)` is treated as success, so retries after lost acks
  neither duplicate nor fail. Raw user strokes flow
  `SendOrchestrator -> PageStore`; synthesized paths are NOT persisted as ink —
  regenerated from `synthesisSeed`; `PageStore` receives only `AssistantChunk`
  (text + seed + chunkIndex) from `ConversationEngine`. `RenderProgress` and
  `StatusChange` use **upsert/replace** semantics (not blind insert).
- **`PageStore` is the single source of truth**; `ConversationEngine` reads and
  writes through it and holds no exclusive message state.
- **One canvas owner (`DrawSurface`)**; `HandwritingRenderer` is a `DrawScope`
  primitive it invokes.
- **Caption + preContext edges:** recognized text is emitted to `DrawSurface` as
  a caption; preceding text is read from `PageStore` to pass as `preContext`.
- **Live-response render driver:** the active assistant response is rendered by a
  render loop hosted in `DrawSurface` that subscribes to the assistant message's
  chunk stream (`ConversationUpdate.AssistantChunk` -> `HandwritingSynthesizer`
  with the chunk's `startGlyphIndex` -> `PageLayout` -> `HandwritingRenderer`).
  Scroll-back re-render of past responses regenerates paths from the persisted
  `synthesisSeed` (no live driver).
- **Lifecycle:** the orchestrator's collectors and the animation driver run under
  `repeatOnLifecycle(Lifecycle.State.STARTED)`; pause on `STOPPED`.

### 3.2 Modules

All I/O-crossing interfaces are `suspend` or `Flow`. Time is injected. Pinned
constants live in `RiddleConfig` (§14).

| Module | Responsibility | Draft interface |
|---|---|---|
| **DrawSurface** | Sole canvas owner + composable entry point. **Pure render** from `pageState`; emits pointer events. `TOOL_TYPE_STYLUS` produces ink; **finger events pass through** to the containing scrollable (`PointerEventPass`/`pointerInteropFilter`); eraser/barrel ignored. **Does not write to `PageStore`.** | `pointerEvents(): Flow<StrokeEvent>`; `pageState: StateFlow<PageSnapshot>` |
| **PauseDetector** | Pure logic: detects writing pause from **raw pointer events**; injectable clock. (Instantiated and driven by `SendOrchestrator`.) | `observe(events: Flow<StrokeEvent>, clock: MonotonicClock): Flow<SendTrigger>` |
| **SendOrchestrator** | **Owns the whole pipeline:** consumes `pointerEvents`; runs `PauseDetector`; owns message creation (`createMessage` on first `Down` -> `activeMessageId`) and serialized per-`Up` stroke `append`s; aggregates the block into one `StrokeBatch`; drives `InkRecognizer`; **conditionally launches** `ConversationEngine` only on auto-send or after ✓/edit; **confirm/edit seam**; cancels in-flight work; single worker on `Dispatchers.Default`; wires synth-edge backpressure; collected under `repeatOnLifecycle(STARTED)`. | `fun attach(pointerEvents: Flow<StrokeEvent>)`; `fun confirm(messageId: Long, editedText: String?)`; `val sendState: Flow<SendState>`; `val caption: StateFlow<String?>`; `val activeMessageId: StateFlow<Long?>` |
| **InkRecognizer** | OCR of a **full writing block** -> text + confidence + alternatives. `preContext` = prior message text for vocabulary priming (null on the first message of a conversation). | `suspend fun recognize(batch: StrokeBatch, preContext: String?): RecognizerResult` |
| **RecognitionModelManager** | ML Kit Russian model lifecycle. | `modelState: Flow<ModelState>`; `suspend fun ensureModel()` |
| **ConversationEngine** | History windowing; injects the persona system message; maps domain `Message` -> network `LlmMessage`; **gated on `privacyAcknowledged`**; on launch **creates a distinct ASSISTANT message** (role=ASSISTANT) via `PageStore.createMessage(conversationId, Role.ASSISTANT)` and emits its id; **normalizes streamed `LlmChunk.Text` into word-boundary chunks**; persists via PageStore (each chunk tagged with a monotonic `chunkIndex` and a per-assistant-message cumulative `startGlyphIndex` so glyph indices are globally monotonic across chunks); maps `LlmErrorCategory -> MessageStatus`/UI. (`LlmProvider` is injected.) | `suspend fun respond(userMessageId: Long, input: RecognizerResult): Flow<ConversationUpdate>` |
| **Summarizer** | Running summary of older turns when the window slides (pluggable; v1 may reuse `LlmProvider`). | `suspend fun summarize(older: List<Message>): String` |
| **LlmProviderFactory** | Constructs `LlmProvider` from `LlmEndpoint` + `RiddleConfig` + credentials (`EncryptedSharedPreferences`) — the single place that touches Android security plumbing. `modelId` resolves from the active persona's `modelId` (override) else `LlmEndpoint.modelId`. | `fun create(endpoint: LlmEndpoint, config: RiddleConfig): LlmProvider` |
| **LlmProvider** | Pluggable **streaming** chat seam (OpenAI-compatible now, local later). **Pure network seam** — takes stripped `LlmMessage`(s), decoupled from persona/settings/persistence. Owns its own retries and emits **typed** `LlmChunk.Error(category, retryable)`. | `suspend fun chat(messages: List<LlmMessage>): Flow<LlmChunk>` |
| **HandwritingSynthesizer** | Text -> **glyph-local** stroke paths (stroke-font + jitter now, ML later); streaming word-boundary chunks. Called per chunk with a `startGlyphIndex` (cumulative per assistant message) so emitted `GlyphPath.glyphIndex` is globally monotonic per response. (Does NOT place on screen.) | `fun synthesize(text: Flow<String>, seed: SynthesisSeed, startGlyphIndex: Int): Flow<List<GlyphPath>>`; plus `synthesizeAll(text, seed, startGlyphIndex): List<GlyphPath>` for replay/tests. |
| **PageLayout** | Compositor: maps glyph-local paths to screen-space `PlacedStrokePath`; line-breaking; cursor; vertical flow. **Streaming-safe:** completed lines are committed (frozen); only the currently-building line is mutable. | `fun layout(content: List<Layoutable>, viewport: Rect, cursor: Cursor): List<PlacedStrokePath>` |
| **HandwritingRenderer** | `DrawScope` primitive; animates placed paths via `PathMeasure` + `nextContour()` pen-lifts; throttled to e-ink cadence. | `fun DrawScope.renderAnimated(paths: List<PlacedStrokePath>, progress: State<Float>)` |
| **EInkDisplayAdapter** | E-ink refresh seam: partial refresh (region) during animation, full refresh after each response; paired begin/end. | `fun beginPartialRefresh(region: Rect?)`; `fun endPartialRefresh()`; `suspend fun fullRefresh()` |
| **PageStore** | Single source of truth; Room-backed, append-only, **idempotent**; **one internal retry**. `createMessage(conversationId, role)` creates a USER or ASSISTANT row. `UserStrokes` dedup by `idempotencyToken`; `AssistantChunk` dedup by `(messageId, chunkIndex)`; `RenderProgress`/`StatusChange` are upsert/replace. | `suspend fun createMessage(conversationId: Long, role: Role): Result<Long>`; `suspend fun append(event: PageEvent): Result<Unit>`; `fun observe(): Flow<PageSnapshot>` |
| **PersonaRepository** | Persona CRUD + validation (**Room-backed**; not secrets). Exactly one active (transactional toggle + partial unique index on `isActive=1`). | `personas: Flow<List<Persona>>`; `suspend fun active(): Persona`; `suspend fun upsert(p: Persona)` |

**Boundary principle:** each module is deep and replaceable. Explicit seams:
`LlmProviderFactory`, `LlmProvider`, `HandwritingSynthesizer`, `Summarizer`,
`EInkDisplayAdapter`, `RecognitionModelManager`, `MonotonicClock`. `DrawSurface`
is pure render; `SendOrchestrator` owns the pipeline; `HandwritingSynthesizer`
emits glyph-local paths; `PageLayout` is the only screen-coordinate owner.

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
                                             │  if multiple new blocks start, only the
                                             │  latest survives (conflation) and is sent
                                             │  once in-flight completes)
```

- The **cancel-window applies only to `paused`/`recognizing`** (pre-network).
  Cancel marks the message `status=CANCELLED` (never re-flushed) and returns to
  `idle`; a new block starts a fresh message.
- **Confidence 0.5–0.7** -> `AwaitingConfirmation`: caption + "edit" (typed
  text) + "✓". `confirm(messageId, editedText?)`: if `editedText` is non-null,
  `SendOrchestrator` emits `PageEvent.Recognized` to overwrite the USER message's
  `recognizedText` **before** launching `ConversationEngine.respond`.
  `ConversationEngine.respond` is **NOT** launched until ✓/edit. **Strokes during
  `AwaitingConfirmation`** extend the current block; the caption updates and OCR
  is re-run on the augmented batch before `confirm()` commits.
- Once the **first `LlmChunk` arrives**, the request is no longer cancelable; new
  strokes during streaming/animating start a **follow-up turn** (new message).
- Only **one** LLM request in-flight; triggers arriving while busy are conflated.
- `complete -> idle` returns the orchestrator to accept the next turn.

### 3.4 Stroke -> message lifecycle

- A **writing block** = strokes from the first `Down` after `idle` until the
  block ends. Block ends when: the message is sent (auto or ✓), or cancelled, or a
  follow-up turn begins.
- **`SendOrchestrator` owns the pipeline.** On the first `Down` of a block it
  calls `PageStore.createMessage(conversationId, Role.USER)` -> `messageId` and
  publishes it via `activeMessageId`. **It then appends per-`Up` stroke batches
  (`append(UserStrokes(messageId, …, token))`) serialized after `createMessage`
  in its own coroutine**, so a fast tap is buffered until the id exists — no
  drops, no mis-attachment. **`DrawSurface` does not persist**; it only renders.
  **`SendTrigger.requestId` *is* that `messageId`** (no separate correlation).
- `SendOrchestrator` aggregates the block's strokes into **one `StrokeBatch`**
  before calling `InkRecognizer.recognize`.
- **Two messages per turn.** Each turn produces two rows: a **USER message**
  (role=USER) created by `SendOrchestrator` on first `Down`, and a distinct
  **ASSISTANT message** (role=ASSISTANT) created by `ConversationEngine` when
  `respond()` launches. `AssistantChunk.messageId` and `RenderProgress.messageId`
  reference the **assistant** message; raw user strokes reference the **user**
  message. The two statuses are independent and unambiguous.
- **USER message** `status`: `PENDING -> RECOGNIZED -> COMPLETE` (when sent; or
  `CANCELLED`). `PENDING` = "not yet sent to the LLM"; OCR may populate
  `recognizedText` while `PENDING`.
- **ASSISTANT message** `status`: `PENDING -> SENDING -> STREAMING -> COMPLETE`
  (or `ERROR`). A `STREAMING` assistant row at cold restart (LLM connection gone)
  is marked `ERROR` with a "tap to retry" UI.
- **Flush** applies to USER messages only — triggers: **privacy re-acknowledge**,
  **connectivity restore**, **credentials configured** — and queries
  `role=USER AND status=PENDING AND recognizedText IS NOT NULL`. (A USER row stuck
  in `RECOGNIZED` after a cold restart is reset to `PENDING` with `recognizedText`
  preserved and re-queued.) **Cancelled rows are never re-flushed.**

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

- Continuous vertical scroll (finger/pointer pass-through, §3.2). User writes
  near the bottom; AI replies below; older entries scroll upward.
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
  (typed text). Editing replaces the text sent to the LLM but **keeps the
  original raw ink on the page** (pure-render; user handwriting is never erased).
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
| UI | Jetpack Compose | single `Canvas` in `DrawSurface` (pure render); `Animatable`/`PathMeasure`; `repeatOnLifecycle`; pointer pass-through |
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

`PageStore` is the single source of truth; append-only + idempotent. (`observe()`
emits full `PageSnapshot` per change — O(N) per emission, acceptable for a v1
notebook of hundreds of messages; a delta Flow is a noted future optimization.)

```
conversation(id PK, createdAt, personaId FK -> persona.id, systemPromptSnapshot)
            -- systemPromptSnapshot captured at the FIRST user message of the
            -- conversation; later persona edits affect only NEW conversations.
persona(id PK, name, systemPrompt, modelId?, maxTokens?, temperature?, isActive)
            -- exactly one isActive=true:
            --   CREATE UNIQUE INDEX persona_active_unique ON persona(isActive) WHERE isActive = 1
            --   (plus a transactional toggle in PersonaRepository); NOT a secret.
message(id PK, conversationId FK, role, displayText, recognizedText?,
        synthesisSeed?, status, createdAt, sortIndex)
            -- role: user | assistant  (one USER + one ASSISTANT row per turn)
            --   USER row: recognizedText may be set; synthesisSeed is NULL
            --   ASSISTANT row: synthesisSeed may be set; recognizedText is NULL
            -- status: pending|recognized|sending|streaming|error|complete|cancelled
            --   USER lifecycle: pending->recognized->complete(/cancelled)
            --   ASSISTANT lifecycle: pending->sending->streaming->complete(/error)
            -- @Index(value=["conversationId","sortIndex"]) for history reads
stroke(id PK, messageId FK, strokeIndex, isUser, pointsJson, idempotencyToken UNIQUE)
            -- raw USER points for replay; messageId -> a USER message; isUser always true in v1
            -- UNIQUE token => per-Up appends are idempotent
assistant_chunk(id PK, messageId FK, chunkIndex, text, seedJson, UNIQUE(messageId, chunkIndex))
            -- streamed assistant text; messageId -> an ASSISTANT message
            -- UNIQUE(messageId,chunkIndex) => chunk-level idempotency
            -- startGlyphIndex is NOT persisted; it is recomputed deterministically
            --   by re-synthesizing prior chunks of the message (seeded)
render_progress(messageId PK, glyphIndex, pathOffset)   -- upsert; 1:1 with assistant msg
conversation_summary(id PK, conversationId FK UNIQUE, upToMessageId, summary)  -- one row per conversation
```

- Raw user strokes live in `stroke` (re-renderable on scroll-back).
- Assistant streamed text lives in `assistant_chunk` (one row per chunk); the
  message's `displayText`/`synthesisSeed` are derived/finalized on `COMPLETE`.
- `render_progress`: on cold restart, glyphs with index < `glyphIndex` are shown
  fully drawn; the glyph at `glyphIndex` resumes from `pathOffset`
  (**normalized `[0,1)` fraction of that glyph's path length**); later glyphs are
  not yet drawn. No animation replay.
- `status` makes the lifecycle queryable; `CANCELLED` rows are excluded from
  flush; `RenderProgress`/`StatusChange` use upsert/replace.

### History windowing (ConversationEngine)

- Sliding window of the **last N turns** (`RiddleConfig.historyWindowTurns`; a
  "turn" = one USER + one ASSISTANT message pair) + a **running summary** of older
  turns sent to the LLM. Summary produced by the `Summarizer` seam, persisted in
  `conversation_summary` (advanced as the window slides; summarize only on slide
  to bound cost). If the `Summarizer` fails, v1 degrades gracefully (omit the
  summary; do not block the turn). N and the summary are observable
  (`historySize(): Int`).

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
| LLM **empty** response | ConversationEngine | 1 LLM auto-retry (new request) **reusing the same ASSISTANT row** with a fresh `SynthesisSeed`; if text then arrives, regenerate paths; else "…" + "tap to retry" |
| LLM **post-retry failure** | ConversationEngine | `status=ERROR`; "tap to retry" UI |
| Cancellation | SendOrchestrator | cancel-window cancels OCR (pre-network only); pen-during-LLM = follow-up turn |
| Missing glyph | HandwritingSynthesizer | word-granularity fallback + alternate ink tone (§8.2) |
| Long response | HandwritingRenderer | progressive via `currentGlyphIndex` (§4.3); never block UI |
| Room write failure | PageStore | `append`/`createMessage` do **one internal retry**; transient = `SQLiteFullException`/disk I/O -> snackbar; permanent = constraint/schema mismatch -> diagnostics hint; duplicates (`UserStrokes` token or `(messageId,chunkIndex)`) are **idempotent success**; `RenderProgress`/`StatusChange` use upsert/replace |
| Wrong-language input | InkRecognizer | Russian-only v1 (§6); low-confidence discard |
| **Hot backgrounding** | SendOrchestrator (lifecycle owner) | pause animation (`STOPPED`); resume from current position |
| **Cold restart mid-response** | SendOrchestrator (restore) via PageStore+Renderer | restore from Room; render assistant response at `render_progress` (no replay); a USER row stuck in `RECOGNIZED` is reset to `PENDING` and re-queued; a `STREAMING` ASSISTANT row is marked `ERROR` + "tap to retry" |

---

## 11. Testing

### Unit (pure logic, no Android)
- **ConversationEngine** — history windowing + system-message injection;
  `Message -> LlmMessage` mapping; word normalization; **monotonic `chunkIndex`
  + cumulative `startGlyphIndex` per assistant message**; assistant-message
  creation (`AssistantCreated` first); `LlmErrorCategory -> MessageStatus`
  mapping; `privacyAcknowledged` gating; fake streaming `LlmProvider`. Dense
  coverage.
- **Summarizer** (fake) — invoked on window slide; summary persisted; graceful
  degradation on failure.
- **PauseDetector** — `observe` with `FakeMonotonicClock`.
- **SendOrchestrator** — `attach(pointerEvents)`; message creation on first
  `Down` + `activeMessageId`; **per-`Up` appends serialized after `createMessage`
  (fast-tap buffering)**; StrokeBatch aggregation; state transitions incl.
  `AwaitingConfirmation` (re-OCR on augmented strokes) and `complete->idle`;
  cancel only from `paused`/`recognizing` -> `CANCELLED`; conflation; conditional
  launch; `confirm(messageId, editedText)`; follow-up turn.
- **HandwritingSynthesizer** (stroke-font) — path counts; empty text;
  word-granularity fallback; seeded-jitter bounds; **determinism** (same seed +
  same text => identical deterministic point sequences); emits glyph-local paths.
- **PageLayout** — line-break/wrap; glyph-local -> placed mapping; committed
  lines frozen during reflow.
- **HandwritingRenderer** — pure progress: path length L, `progress=0.5` =>
  offset 0.5L.
- **LlmProvider** (real) — MockWebServer, streamed OpenAI protocol; typed
  `LlmErrorCategory` mapping; retry policy; takes `LlmMessage` (no persistence
  fields).
- **PageStore** — Room in-memory; `createMessage` returns id; `UserStrokes`
  idempotency (repeated token => success, no dup); `AssistantChunk` idempotency
  (repeated `(messageId,chunkIndex)` => success, no dup); `RenderProgress`/
  `StatusChange` upsert; single retry; transient vs permanent; `CANCELLED`
  excluded from flush; `RECOGNIZED`/`SENDING` re-queued on cold restart.

### UI / Compose
- Inject pointer events into `DrawSurface`; assert stylus => ink; **finger passes
  through** (scroll container receives it); eraser/barrel ignored; **DrawSurface
  performs no `PageStore` writes**.

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
- **LLM:** **streaming** abstraction; **pure network seam** (takes `LlmMessage`,
  decoupled from persona/settings/persistence; constructed by
  `LlmProviderFactory`); typed errors (`LlmErrorCategory`); privacy gating in
  `ConversationEngine`.
- **Handwriting:** stroke fonts + jitter now (verified open-path + fallback), ML
  later via `HandwritingSynthesizer`; **synth emits glyph-local paths**;
  `PageLayout` is the only screen-coordinate owner; synth->renderer `SUSPEND`.
- **Persona:** configurable; `systemPrompt` **snapshotted per conversation at the
  first user message**; exactly one active.
- **Ownership:** **`DrawSurface` is pure render**; **`SendOrchestrator` owns the
  whole stroke->message pipeline** (pointer events -> create USER message ->
  append strokes -> recognize -> conditionally send), eliminating the
  create/append race. **Two messages per turn** (USER + ASSISTANT); assistant
  glyph indices are globally monotonic per response (`startGlyphIndex` per chunk).
- **Send trigger:** auto-send on pause (1500 ms) + cancel-window (800 ms; pre-
  network only) + `AwaitingConfirmation` for 0.5–0.7 (edit = typed text); engine
  launched **only** on auto-send or ✓.
- **Async shape:** `suspend`/`Flow` end-to-end with explicit backpressure; one
  canvas owner; **finger events pass through to scroll**; PageStore single source
  of truth (append-only **idempotent** — `UserStrokes` by token,
  `AssistantChunk` by `(messageId, chunkIndex)`, upsert for progress/status; one
  internal retry); explicit SendOrchestrator state machine;
  `repeatOnLifecycle(STARTED)`.
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
data class LlmMessage(val role: Role, val content: String)   // stripped; LlmProvider takes this, not the DB entity
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

sealed interface ConversationUpdate {     // ConversationEngine.respond(userMessageId, …) emissions
    data class AssistantCreated(val userMessageId: Long, val assistantMessageId: Long) : ConversationUpdate  // first emission
    data class AssistantChunk(val messageId: Long, val chunkIndex: Int, val text: String, val seed: SynthesisSeed, val startGlyphIndex: Int) : ConversationUpdate  // messageId = assistant
    data class StatusChange(val messageId: Long, val status: MessageStatus) : ConversationUpdate
}
// (Recognition results are owned by SendOrchestrator/InkRecognizer, NOT emitted here.)

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
sealed interface PageEvent {
    data class UserStrokes(val messageId: Long, val strokes: List<Stroke>, val token: IdempotencyToken) : PageEvent           // idempotent by token
    data class Recognized(val messageId: Long, val result: RecognizerResult) : PageEvent
    data class AssistantChunk(val messageId: Long, val chunkIndex: Int, val text: String, val seed: SynthesisSeed) : PageEvent // idempotent by (messageId,chunkIndex)
    data class RenderProgress(val messageId: Long, val glyphIndex: Int, val pathOffset: Float) : PageEvent                     // upsert; pathOffset in [0,1)
    data class StatusChange(val messageId: Long, val status: MessageStatus) : PageEvent                                        // upsert/replace
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
