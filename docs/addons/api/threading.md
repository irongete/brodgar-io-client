# Threading and Execution Model

How Lua callbacks execute within the client's internal frame loop and threading architecture.

---

## 1. Single-Threaded Lua Execution

Although the underlying Java game engine uses multiple background threads for networking, map streaming, and audio playback, **all addon Lua code runs synchronously on the main client thread**:
* Event handlers, timers, hotkeys, and console commands execute sequentially during the client's main update tick.
* `Draw` callbacks execute on the UI rendering pass immediately before frames are presented to the screen.

Because execution is single-threaded, you do not need mutexes or locks when accessing Lua state.

---

## 2. Non-Blocking Callbacks

Because addon callbacks execute directly inside the game loop:
* **Avoid blocking operations**: Long-running loops, heavy calculations, or repetitive deep tree-searches will lower the game's frame rate.
* **Instruction Limits**: Any single callback exceeding **10,000,000 Lua instructions** will be terminated immediately by the engine's watchdog.
* **Tick Budgets**: Consuming more than **10 milliseconds** of Lua CPU time per tick for 30 consecutive ticks will trigger an automatic disable of the addon.

---

## 3. Asynchronous Operations

Operations that involve external systems are strictly non-blocking and return immediately via callback events:
* **HTTP Requests (`hafen.http`)**: Dispatched to background worker threads. The `:on("done", callback)` handler is queued back onto the main client thread once the network response arrives.
* **WebSocket Messages (`hafen.websocket`)**: Handled via `:on("message", callback)` dispatched on the main thread.
* **Persistent Storage (`hafen.store`)**: Flushed to disk asynchronously by the background database worker.
