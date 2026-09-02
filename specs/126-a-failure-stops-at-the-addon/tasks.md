# 126 — tasks

- [x] **126.1 — an Error is contained at the choke point and quarantines its addon.**
      `AddonManager.callLua` grows a `catch(Throwable)` after its two, rethrowing `ThreadDeath` and anything
      caught while the thread is interrupted, and filing everything else on a new
      `ConcurrentLinkedQueue<Quarantine>`. `enforceSoftBudget` drains it at end of tick into the existing
      `autoDisable`, so the announcement, the panel row and the until-next-load rule are the ones already
      there; a queued `consoleOwner` is logged and dropped. Revises `runtime.md` (what an `Error` does) and
      adds the `boot-and-loop.md` gotcha that `UILoop` catches only `InterruptedException`. **Discharges the
      feature's impact set**: the seven pages stating the isolation promise, each revised or discharged with
      its reason, `font.md:97` among them as a different subject.
      *Its suite* registers a console command that recurses without bound, and asserts before offering it
      that an ordinary `RuntimeException` from a bridge verb is still caught and still leaves the addon
      running — the containment that already worked, so a regression there is caught here.
      `[manual]`: run `:126crash`, then open Options → AddOns — expect: the client still running, and this
      addon's row reading `auto-disabled`.

- [x] **126.2 — the instruction budget is one per entry, whatever thread entered.**
      `Sandbox.Watchdog` stops holding one `remaining` per environment and confines it instead: a plain
      `long` for `UILoop.th`, reached by one reference compare in `onInstruction`, and a
      `ConcurrentHashMap<Thread, long[]>` for the off-thread entries an action or inbound-message handler
      makes. `arm()` claims the right slot and `callLua`'s `finally` releases the map entry, so a Loader
      thread leaves nothing behind. `INSN_CAP <= 0` still disables the cap. Revises `runtime.md`'s watchdog
      section to say the budget is per entry into your Lua, not per environment.
      *Its suite* drives a loop past the cap and asserts it raises naming `instruction budget exceeded`, then
      calls a callback that burns a large fraction of the cap **twenty times in a row** and asserts every one
      returns — which is the assertion that each entry is armed afresh rather than draining one shared
      counter, and the check that fails today if the budget is ever made cumulative.
      <!-- extra context: src/haven/UILoop.java for `th`'s declaration and its assignment order -->

- [x] **126.3 — encode refuses a table too deep instead of overflowing the stack.**
      `Json` threads a depth through `write`/`writeTab` against `DEFAULT_MAX_DEPTH`, the cap and the system
      property `parse` already reads. It follows the rule the cycle guard set: strict mode raises, forgiving
      mode writes a placeholder beside `"<cycle>"`, so the store's flush and the `:lua` result survive a deep
      table that `hafen.json():encode` refuses. `json.md` states the cap for `encode` where it states it for
      `parse` today.
      *Its suite* builds a table nested past the cap, `pcall`s `encode` on it, and asserts the call failed
      **and** that the message names the depth limit — not merely that it failed, since an overflow fails too
      and is what this task removes. It then encodes a table one level inside the cap and asserts it
      round-trips through `parse`, which is the check that the cap was not set an off-by-one too tight.

- [x] **126.4 — a model's accessors are bounded before anything is allocated.**
      `Gltf` validates an accessor's `count` against a cap and computes `count * comps` in `long` before any
      `new float[…]`; a `bufferView`'s `buffer` is range-checked rather than indexed with its `-1` default;
      index values are checked against the vertex count. Every refusal goes through the existing
      `err(name, …)`, so it names the model and what is wrong. `models.md` gains what a malformed model does,
      against its own promise that an unsupported one "fails with an error that names the feature".
      *Its suite* ships three tiny hand-written `.glb` files — an accessor `count` past the cap, a
      `bufferView` with no `buffer`, an index past the vertex count — loads each through
      `hafen.asset():get`, and asserts each `pcall` failed **and** that the message names that fault rather
      than reading `null` or an array index, which is what each of the three produces today.

- [ ] **126.5 — a node reached twice is refused as the invalid document it is.**
      `Gltf.walk` keeps `visiting` for the cycle case and gains a `seen` array enforcing glTF's own
      invariant, that a node has at most one parent. A document reaching a node twice is refused naming that,
      which bounds the walk to the node count without a budget to tune and without discarding an instance —
      a node under two parents is the same mesh with a different baked transform, so skipping the second
      visit would silently drop geometry. `models.md` records the invariant beside the unsupported list.
      *Its suite* ships two `.glb` files: one whose node graph is a diamond, asserted to fail with a message
      naming the repeated node, and one deep single-parent chain of mesh-less nodes asserted to **load**,
      which is the check that the fix bounded the abusive document without refusing a legal deep one.
      `[manual]`: none — both files are shipped and both outcomes are read back.

- [ ] **126.6 — an accessor's byte range is checked against its buffer.**
      `Gltf.readVecs` and `readIndices` compute, in `long`, the last byte their stride walk reads —
      `start + ((count - 1) * stride) + (comps * csz)` — and refuse past `buf.length` through the existing
      `err(name, …)`. A `.glb` whose accessor overruns its buffer raises an `ArrayIndexOutOfBoundsException`
      today, naming neither the model nor the fault: it is contained and it is a failed load, so it breaks no
      acceptance criterion, and it is the one malformed case `models.md`'s own paragraph does not deliver.
      No page changes — the promise is already written there.
      *Its suite* ships a `.glb` whose POSITION accessor declares three vertices over a twelve-byte buffer and
      one whose index accessor overruns its view, loads each through `hafen.asset():get`, and asserts each
      refusal names the model **and** the overrun rather than a Java array index. It re-asserts that a legal
      indexed model still loads, since the new check sits on the path every accessor takes.
