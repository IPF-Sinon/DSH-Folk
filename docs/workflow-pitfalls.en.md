# Automation Workflows: Developer Notes and Pitfalls

This document is for **people changing the workflow feature in DSH-Folk** (and for users who want
agents to produce reliable results). It does not restate the DSL — that is the job of
`dsh-workflow --help` and `samples` — it records **the traps we actually hit**, especially the
ones where "the code looks right but the behaviour is wrong".

> License: this document is released under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
> Keep the attribution and this notice when sharing or adapting it.
> The code itself remains GPL-3.0 (see the project LICENSE).

---

## Contents

1. [What it is made of](#1-what-it-is-made-of)
2. [The cross-process contract: a forked DSL fails silently](#2-the-cross-process-contract-a-forked-dsl-fails-silently)
3. [Approval: blocking semantics and the 60-second TTL](#3-approval-blocking-semantics-and-the-60-second-ttl)
4. [The engine: branches, skipping and timeouts](#4-the-engine-branches-skipping-and-timeouts)
5. [Blocking on DSH and its branches](#5-blocking-on-dsh-and-its-branches)
6. [The Compose canvas: seven rounds of gesture and coordinate rework](#6-the-compose-canvas-seven-rounds-of-gesture-and-coordinate-rework)
7. [Kotlin details that compile yet break at runtime](#7-kotlin-details-that-compile-yet-break-at-runtime)
8. [Tool registration and degradation](#8-tool-registration-and-degradation)
9. [How to verify without a local compiler](#9-how-to-verify-without-a-local-compiler)
10. [Where to put prompts and docs for the agent](#10-where-to-put-prompts-and-docs-for-the-agent)

---

## 1. What it is made of

One workflow crosses **four process/language boundaries**:

```
agent inside the container
  ↓ tool call (dsh-folk-workflow.mjs, JS)
dsh-workflow CLI (node script inside the rootfs)
  ↓ HTTP (loopback bridge 127.0.0.1, token auth)
WorkflowBridge (Kotlin, app side)
  ↓ approval dialog → WorkflowApprovals (state machine)
WorkflowEngine (Kotlin, coroutines)
  ↓ execRootfs (proot / proroot / chroot)
actual execution inside the container
```

**The premise of every pitfall below**: if any layer's "format assumption" disagrees with another,
the symptom is a **silent runtime failure** (the agent says "created" but the list is empty, or the
run stops halfway). So the contract must have exactly one source of truth.

| Layer | File | Responsibility |
|---|---|---|
| Tools and prompts | `app/src/main/assets/dsh-folk-workflow.mjs` | registers 4 tools, injects the DSL doc into the system prompt, writes the self-check status file |
| CLI | `DshRuntimeScripts.WORKFLOW_CLI_SCRIPT` | save/read/validate/run; the only thing that speaks HTTP |
| Bridge | `WorkflowBridge.kt` | HTTP endpoints, approval orchestration, starting a run |
| Engine | `WorkflowEngine.kt` | node scheduling, branches, timeouts, state persistence |
| Model and validation | `WorkflowModels.kt` / `WorkflowValidator.kt` | the only data structures and legality rules |

---

## 2. The cross-process contract: a forked DSL fails silently

### Trap: mismatched field names — the compiler cannot help

An early spec document said `edge.fromNodeId` / `edge.toNodeId`, while **the real fields are
`from` / `to`**:

```kotlin
data class WorkflowEdge(val from: String, val to: String, val whenExpr: String = "")
```

The CLI is JS, the bridge is Kotlin, and nothing type-checks across them. Code copied from the
doc either fails to compile or **parses into empty edges** (Gson defaults missing fields, so
"all the connections vanished" without an error).

**Rule**: any cross-language field name must follow `WorkflowModels.kt`; never copy doc snippets
verbatim. `check-workflow.js` contains a real CLI JS harness that actually runs `dsh-workflow`
parsing — the only thing that catches this class of divergence.

### Trap: node state has 7 values, not the 5 an old doc mentions

```kotlin
internal enum class WorkflowStepState(val id: String) {
    PENDING("pending"), RUNNING("running"), OK("ok"), FAILED("failed"),
    TIMEOUT("timeout"), SKIPPED("skipped"), CANCELED("canceled"),
}
```

Early docs said `SUCCEEDED`; the real value is **`OK`**. These `id` strings are persisted in
`workflow-runs/*.json`, so renaming one makes historical runs unreadable. **Do not rename them to
match a document.**

### Trap: coordinates are `Int` (dp), not `Float`

```kotlin
val posX: Int = 0,   // dp, snapped to a 12dp grid
val posY: Int = 0,
```

A doc suggested `0f`. Switching to `Float` would break the on-disk format, grid snapping and the
canvas hit test at once. **Adding fields is fine; changing an existing field's type is not.**

### Trap: `schemaVersion` lives in two places

`WorkflowSchema.SCHEMA_VERSION` and the constant inside the CLI. Change one and either every old
definition or every new definition is rejected.

---

## 3. Approval: blocking semantics and the 60-second TTL

### Creating is yellow, running is red — two separate commands

```kotlin
val accent = if (creating) Color(0xFFB58900) else MaterialTheme.colorScheme.error
```

Do not merge them into one "execute" command: after creating, **nothing has run**; running can
cause damage. The user's mental load differs completely.

### Trap: the TTL timer must pause while editing

If the 60-second TTL keeps ticking while the user edits the definition, they finish and find the
approval expired — wasted work.

```kotlin
const val TTL_MS = 60_000L
fun startEditing(id: Long)   // pause the countdown, hand out a fresh full TTL
```

In `WorkflowApprovals`, editing and approving are **two states**, not variants of one. Both saving
and cancelling restart the clock.

### Trap: the diff must be computed against the submitted definition

```kotlin
diff = workflowDiff(submitted, edited)
```

`submitted` is what the AI proposed, `edited` is what the user changed. **Never diff against the
last saved version** — the user would see a pile of changes unrelated to this edit and start
doubting what they are approving.

The diff must travel back to the AI (`diff` and `diffText`), otherwise it keeps working from its
own (now stale) understanding.

### Trap: a rejection reason must reach the AI

`rejectReasons: ConcurrentHashMap<Long, String>`. Even an empty reason must return an explicit
"user rejected" rather than a timeout or a 500 — otherwise the agent retries and the dialog
pops again and again.

---

## 4. The engine: branches, skipping and timeouts

### Trap: branches that were not taken must be SKIPPED, not vanish

After a successful run, walk the unvisited nodes:

```kotlin
val visited = run.steps.map { it.nodeId }.toSet()
// every other node → WorkflowStepState.SKIPPED, reason = "branch_not_taken"
```

**Why**: the graph shows eight nodes, the run detail shows five steps. Without the marker the user
assumes the engine skipped work. With it, "this path was not taken" is a fact rather than a hole.

### Trap: `onError` interacts with "is there a false branch"

```kotlin
if (!up && node.onError == WorkflowOnError.STOP && !hasFalse) {
    failRun(ctx, runId, "dsh_unavailable")
}
```

`hasFalse = def.outgoing(node.id).any { it.whenExpr == "false" }`. In other words: **if a false
branch is drawn, follow it; only fail the whole run when there is none** and `onError` says stop.
Checking `onError` first makes flows that drew a recovery branch fail anyway.

### Timeouts default to 120 seconds, not 60

```kotlin
val timeoutMs: Long = 120_000L   // 1000..3600000
```

The per-step timeout is **per node** and has nothing to do with the approval TTL (60 s). Confusing
them kills long-running commands.

---

## 5. Blocking on DSH and its branches

The `trigger` node has two switches with **different** semantics:

| Config | Behaviour |
|---|---|
| `requireDsh = "0"` | no check, no start; take the `true` branch immediately |
| `requireDsh = "1"` | `awaitRunning(timeoutMs)`, result stored in `branches[node.id]`, then split by `whenExpr` |
| `autostartDsh = "1"` (with the above) | call `HarnessService.start()` first, then wait |

**Key point**: with `requireDsh=1` and a drawn `false` edge, "DSH is not up" takes the false branch
(e.g. "notify the user to start it manually") and does **not** fail the run. This is the most
commonly misread part: it covers both "was never running" and "start timed out", because the exit
is the same `up == false`.

---

## 6. The Compose canvas: seven rounds of gesture and coordinate rework

This is the longest section because every trap here is **"the code looks entirely reasonable, yet
on a real device it simply does not track your finger"**.

### 6.1 Never let two things fight over gestures

First version: card `clickable` + card `pointerInput` drag + canvas `detectTransformGestures` for
pan/zoom. On device: **the card would not move**. Three consumers competing; who wins depends on
details, and the result is unpredictable.

**Final architecture** (modelled on the tap-assistant, whose cards are each a View with their own
`onTouchEvent`):

- **the card itself** handles tap / drag / wire-drag from its pin — no hand-computed hit test;
  a finger on the card delivers events to that card;
- **the canvas** only handles dragging on empty space (panning);
- nobody converts hit rectangles between two coordinate systems.

### 6.2 `pointerInput` keys must be `Unit`, and callbacks need `rememberUpdatedState`

The most repeatedly-hit trap. First version:

```kotlin
Modifier.pointerInput(node.id) { detectDragGestures { _, amount -> onDrag(amount) } }
```

`onDrag` is the closure captured **on first composition**. The key does not change → Compose does
not restart the gesture block → dragging uses the **original** coordinates. On device: **the first
drag works, the second jumps back to the starting position**.

Fix (all parts required):

```kotlin
val dragCb = rememberUpdatedState(onDrag)
Modifier.pointerInput(Unit) { detectDragGestures { _, amount -> dragCb.value(amount) } }
```

Same for coordinates: card positions change every frame, so using `positions` as the key
**cancels and rebuilds** the gesture detector continuously and applies the displacement twice —
the drag feels slippery or accelerating.

### 6.3 Do not snap to the grid on every frame while dragging

```kotlin
// wrong: snapping each delta swallows sub-half-cell (6dp) movement → trembles in place
val nx = snapToGridDp(node.posX + dxDp)

// right: accumulate **unsnapped** movement while dragging; snap on release
dragAcc += dxDp
// preview position = origin + dragAcc (1:1 tracking)
// onDragEnd → draft = snapToGridDp(...)
```

On device: **it trembles in place** (shakes without moving).

### 6.4 Keep coordinate conversion in exactly one place

There used to be three: `dp × density` in the canvas, `+ pan / density` in the card offset, and
`dxPx / density` on the host side, plus `Int` truncation. Result: small movements swallowed,
positions drifting.

```kotlin
internal object WorkflowGeometry {
    const val GRID_DP = 12
    fun pxToDp(px: Float, density: Float): Float = if (density > 0f) px / density else px
    fun snapToGridDp(dp: Float): Int = (dp / GRID_DP).roundToInt() * GRID_DP
}
```

A gate carries an **independently recomputed numeric contract** (`density=2.75`, 110px → 40dp →
snap to 36dp), so changing the formula turns it red.

### 6.5 `pan` may be applied only once

Applied once in `graphicsLayer` and again in the card `offset` → positions drifted by 2×. Now it
is applied only in `graphicsLayer`.

### 6.6 Wire dragging: attach the gesture to the **pin dot**, not the whole column

First version attached the drag to the "output pin column", so `onDragStart` delivered the
**column's** local coordinates, which were then added to the card origin → the wire head was
**far to the left**.

Fix: attach the gesture to the pin itself (26dp touch target, unchanged visual dot); wire head =
dot centre, finger = dot origin + local coordinates, **all in window coordinates**.

Existing connections must also start from the **real pin** (each card reports `boundsInWindow()`
per pin), otherwise every wire leaves from the card's top-right corner and looks disconnected.

### 6.7 Drop positions must follow the release point

Three drag-and-drop paths **must share one conversion**:

| Path | Where the position comes from |
|---|---|
| dragged out of the drawer | window coordinates at release |
| wire dropped on a card | connect; no node created |
| wire dropped on empty space | release coords → menu → create node there and connect |

The first version dropped the release coordinates when opening the menu, so the menu built the
node at a default grid slot — the new card appeared somewhere else.

**Ghost card alignment**: the pill's top-left = finger = node top-left. We once shifted the pill
by 40/20dp by hand, which made the drop look like it landed to the bottom right.

### 6.8 No scroll container inside the drawer

The drawer drags **vertically**, and `verticalScroll` consumes vertical drags first, so the child's
`detectDragGestures` never fires. On device: **cards cannot be dragged out of the drawer**.

Fix: seven types in two columns, four rows, fixed height, no scrolling.

> A self-proof: our gate "no scroll container inside the drawer" caught the word
> `verticalScroll` **inside our own comment** on its first run — the assertion really works.

### 6.9 Model and rendering must agree

New flows and AI-generated definitions have **all-zero coordinates**, while the canvas displays
auto-laid-out positions. Dragging then starts from the model's 0 → it jumps immediately.

Fix: when opening the editor, write the auto layout **into the model** (as a baseline), and diff
against that baseline (auto layout is not a user edit, so cancelling must not ask "discard
changes").

### 6.10 Cycle prevention: exactly one decision point

```kotlin
WorkflowValidator.addEdgeIfAcyclic(def, fromId, toId)  // the only way to add an edge
```

There are three edge-entry paths (pin onto a card, empty-space menu creating a node, `addNode`'s
`afterId`). Miss the check on one and cycles leak in — and a cycle in the definition makes the
engine's topological scheduling loop forever.

Note: the two `addNode` sites connect a **brand-new node** (no outgoing edges yet, so a cycle is
impossible by construction), so they need no check — write the gate against that real invariant
rather than demanding "zero direct edge construction".

---

## 7. Kotlin details that compile yet break at runtime

### 7.1 `this` inside a lambda is not the outer class

```kotlin
// wrong: this refers to the lambda itself → Unresolved reference
this.setState { s -> s.copy(message = this.str(R.string.x)) }

// right: hoist it first
val msg = this.str(R.string.x)
this.setState { s -> s.copy(message = msg) }
```

`withContext(Dispatchers.IO) { this.installArchive(...) }` hits the same wall — `this` is the
**coroutine scope**. Fix: `val self = this`, then use `self` inside.

### 7.2 An interface member clashing with an existing private member → Conflicting overloads

Adding `override` to an object that already has a `private fun` with the same signature conflicts
(Kotlin does not treat private members as interface implementations). Two ways out:

- widen the private member to `internal` (first confirm **zero external callers**);
- or **narrow the interface** so it does not demand what only extension functions provide
  (extension functions **cannot** satisfy interface members).

### 7.3 A bare call inside an object resolves to the **member**, not the package extension

```kotlin
// inside the object, given an existing `private fun prefs()` and an interface member `prefs()`
override fun prefs() = prefs()   // ← infinite recursion, stack overflow at runtime
```

Fix: explicit private aliases (`hostPrefs()`) or renamed implementations (`*Impl`); do not rely on
the same-package extension function to save you.

### 7.4 `Shell.Result.isSuccess` is `Boolean?`

```kotlin
if (ok == true) ...   // cannot write if (ok)
```

### 7.5 Use raw strings for shell commands containing quotes or `$`

```kotlin
// wrong: \\" becomes a literal \" in a normal string, closing it early
.add("if command -v \\"\\$c\\" ...")

// right
val probe = """
    if command -v "${'$'}c" >/dev/null 2>&1; then ...
""".trimIndent()
```

### 7.6 Writing `/xxx/*` inside a KDoc opens a nested comment

Kotlin block comments **nest**. Writing `` `/fs/*` `` in documentation increases the depth and the
whole file reports "block comment unterminated". Reword it ("the fs endpoint family").

### 7.7 Cross-file reachability: compute the dependency closure before moving code

Moving a function to a new file in the same package fails to compile as soon as it touches an
original `private` member. **Before moving**, script the closure and check visibility — do not
forget **`const val`** and **`private-in-file` types** (an `internal fun` returning a private type
is rejected).

### 7.8 Do not write Kotlin properties in `.java` files

```java
tarball.isFile        // wrong: Java needs isFile()
```

`kt-lint` only reads `.kt`, so this waits for javac. `tools/check-java-syntax.js` now guards it.

---

## 8. Tool registration and degradation

### Trap: cordis throws for services not declared in `inject`

```js
// wrong: declare only systemPrompt, then read ctx.tools → cannot get property 'tools' without inject
export const inject = ['systemPrompt']

// right
export const inject = ['systemPrompt', 'tools']
```

It does not return `undefined`, it **throws**, and the whole entry fails to activate — losing even
the prompt section.

### Persist the degradation so settings can tell the truth

Tool registration depends on the shape of dsh's tool API (it differs between versions), so there
are three levels:

1. `defineTool` from `@deepseek-ai/dsh-tools`;
2. register the bare spec with `ctx.tools.register`;
3. keep only the prompt section (no tools, but no lost capability — the agent can fall back to the
   `dsh-workflow` CLI).

Write the verdict to `/root/.dsh/workflow-tool-status.json` (`ok` / `degraded` / `unavailable` plus
a reason). Without it, "the agent says there is no such tool" is undiagnosable: is the plugin
missing, or is the API incompatible?

---

## 9. How to verify without a local compiler

This project's development environment has **no local Android SDK** (memory limits make Gradle
impossible), so CI is the only compiler. Every "it passes locally" tool is therefore a **filter**,
not proof:

| Tool | Catches | Misses |
|---|---|---|
| `node tools/check-*.js` (35 of them) | lost behaviour, contract divergence, missing strings | every type error |
| `node tools/kt-lint.js` (string-aware) | braces, templates, imports, Compose hygiene | types, visibility, unresolved refs |
| `check-java-syntax.js` | Kotlin properties in Java | other Java errors |
| structural boundaries + byte comparison | over-deletion / missed moves | semantic equivalence |
| CI (the only truth) | all compile errors | runtime behaviour |

**Working discipline that follows**:

1. change **one cohesive cluster at a time**, one commit per step, wait for green CI before the next;
2. for moves use "structural boundary (up to the next 4-space member) + a tail-shape assertion";
   **do not** write your own brace matcher to slice Kotlin — quotes inside string templates derail
   the state machine (we once deleted 159 extra lines that way);
3. right after moving key functions, **verify immediately**: zero leftover symbols, target still
   present, line counts match;
4. **never push with a red gate** (chain commands so red exits immediately);
5. assertions themselves can be wrong — one of ours encoded the **incorrect** "fall back to
   `baseArgv()`" behaviour and only surfaced while fixing the code. When changing an
   implementation, read the assertion too.

---

## 10. Where to put prompts and docs for the agent

### Put the format on the channel least likely to be lost

In practice the agent got stuck on "I do not know the format". Three channels, by reliability:

1. **`FORMAT` embedded in the tool description** — always reaches the model with the schema;
2. `/root/.dsh/workflow-dsl.md` (written by the host) — depends on "written into the container"
   and "plugin activated";
3. the system-prompt section — also depends on plugin activation.

So put the quick reference in (1), the long form in (2), and only "this capability exists and how
to start" in (3).

### Tool descriptions must state **failure semantics**

Not just "returns a runId":

- non-blocking by default (`wait=false`) → returns `runId`; follow with `workflow_status`;
- `wait=true` has an upper bound; on timeout it returns `state: "still_running"` instead of hanging;
- an invalid definition is rejected **before** execution (with the error list), never partially run.

### Status queries should return `logNextLine`

```json
{ "runId": "...", "fromLine": 42 }
```

The reply carries `logNextLine`; the AI passes it as `fromLine` next time, so it never re-reads the
whole log (and never floods its context with logs).

---

## Appendix: files and gates

| If you change | Run |
|---|---|
| DSL / validation / engine | `node tools/check-workflow.js` (contains a real CLI harness) |
| canvas / editor | the same file (gesture structure, coordinate contract, cycle assertions live there) |
| bridge / guard | `check-workflow.js` + `check-native-caps.js` |
| strings | `check-strings.js` + `check-i18n.js` (zh/en entry counts must match) |
| any Kotlin | `node tools/kt-lint.js $(find app/src -name '*.kt')` |
| any Java | `node tools/check-java-syntax.js` |

The authoritative list is the `Static source checks` step in `.github/workflows/build.yml` — those
are **the 35 gates every release must pass**.
