package io.brodgar.addon;

import haven.Loading;
import haven.Waitable;

import java.util.function.Consumer;

/**
 * M2 of {@code specs/addons/042-event-driven-reads} — the {@link Waitable} wrapper every "a value is still
 * loading" read in the addon layer goes through, instead of a per-frame retry. The client already answers
 * <i>"tell me when this resolved"</i> ({@link Loading} {@code implements Waitable}); nothing in {@code
 * io.brodgar} used it before this task (D-182).
 *
 * <p><b>Retry-on-notify, not wait-once.</b> {@link #on} registers a one-shot continuation on {@code l}; when it
 * fires, {@code retry} runs again. If that throws a <i>different</i> {@link Loading} (a second resource, a
 * further tile), {@code Resolve} re-registers on the new one — the client's own {@link Waitable.Checker} shape.
 * Bounded by {@link #DEFAULT_MAX_RETRIES}: past that the value is left unresolved and <b>stays</b> unresolved
 * until something else announces it — never a fallback poll.
 *
 * <p><b>Not every retry chain has a stable identity (042.12 finding).</b> The default bound assumes a read
 * that is blocked on ONE thing resolving (a specific tile, a specific resource) — one or two re-registrations
 * cover it. {@code MapView.addClientGob} breaks that assumption: it can throw {@code Defer.NotDoneException}
 * ("Finalizing texture in …") for whichever GL texture upload the render backend happens to be busy with at
 * that instant — a <i>different</i>, unrelated resource on every retry during a heavy load (many textures
 * finalizing in sequence, not one). Each retry is still genuinely event-driven (fired by that texture's own
 * completion, never a timer), so chaining many of them is not a poll — it is exactly what the pre-042.12
 * per-tick {@code armPending} did, which had no bound at all. {@link #on(Loading, Addon, Retry, int)} lets a
 * caller whose retry chain can legitimately rotate through many distinct blockers ask for a higher bound than
 * {@link #DEFAULT_MAX_RETRIES} instead of quietly giving up mid-load.
 *
 * <p><b>Marshalling (P5).</b> {@code Waitable.wnotify()} runs on whichever thread completed the load (a Loader
 * thread, a {@code Defer} pool thread) — including, for some {@link Waitable}s, <i>inline</i> on the
 * registering thread if the value is already resolved. Either way {@code retry} never runs synchronously here:
 * it is hopped onto the UI-thread tick via {@link AddonManager#enqueueResolve}, so a caller holding some other
 * lock (an entity monitor, the map DB's) is never re-entered.
 *
 * <p><b>Ownership (P2).</b> Every {@link Waitable.Waiting} this mints is registered in the owning {@link
 * Addon}'s resource registry ({@link Addon#waitings}), so {@code :reload}/disable cancels it — and the
 * marshalled callback re-checks the addon is still live before running {@code retry}, since the cancel and the
 * notify can race on two threads. {@code owner} may be {@code null} for a retry that belongs to no single
 * addon (a shared engine-level read); such a retry runs until it resolves or exhausts {@link #MAX_RETRIES},
 * with nothing to cancel it early.
 *
 * <p><b>Refusal path.</b> A <i>bare</i> {@code new Loading(...)} (e.g. {@code GItem.sprite()}) is not
 * waitable — its {@link Loading#waitfor} throws {@link Loading.UnwaitableEvent}. {@code Resolve} catches it and
 * gives up silently (never throws it onward, never logs): that is the signal the read belongs in the spec's
 * stated boundary (D-092) rather than behind a hidden retry, and it is routine enough (an equipped item's
 * sprite mid-build, hit on every fast gear swap) that logging it every time would be noise with no new
 * information after the first occurrence. The blocking helpers ({@code Loading.waitfor(Indir)},
 * {@code queuewait}, {@code waitforint}) are never used — they park the calling thread.
 */
final class Resolve {
    private Resolve() {}

    /**
     * A read that may still be loading. Thrown {@link Loading}s are what drive re-registration; anything else
     * escaping is a real bug and is logged, not retried.
     */
    interface Retry {
        void run() throws Loading;
    }

    /** Past this many re-registrations for one logical read, give up — see the class doc. Never a poll. */
    private static final int DEFAULT_MAX_RETRIES = 8;

    /**
     * Register interest in {@code l} resolving, then run {@code retry} — marshalled onto the tick, owned by
     * {@code owner} (or nobody, if {@code null}) so {@code :reload}/disable can cancel it before it fires.
     * {@link #DEFAULT_MAX_RETRIES}, for a read blocked on one stable thing.
     */
    static void on(Loading l, Addon owner, Retry retry) {
        register(l, owner, retry, 0, DEFAULT_MAX_RETRIES);
    }

    /**
     * As {@link #on(Loading, Addon, Retry)}, with an explicit retry bound for a read whose blocker can
     * legitimately rotate through many distinct, unrelated things (see the class doc's 042.12 finding) —
     * still never a fallback poll, since every one of {@code maxRetries} steps is a real notify, not a tick.
     */
    static void on(Loading l, Addon owner, Retry retry, int maxRetries) {
        register(l, owner, retry, 0, maxRetries);
    }

    private static void register(Loading l, final Addon owner, final Retry retry, final int depth, final int maxRetries) {
        if(depth >= maxRetries) {
            AddonManager.logDiag("Resolve: gave up after " + maxRetries + " retries on " + l);
            return;
        }
        final Waitable.Waiting[] handle = new Waitable.Waiting[1];
        try {
            l.waitfor(() -> {
                if(owner != null)
                    owner.waitings.remove(handle[0]);
                AddonManager.enqueueResolve(() -> {
                    // The cancel and this notify can race on two threads (plan.md gotcha 10) — re-check
                    // liveness here, inside the marshalled step, not at registration time.
                    if((owner != null) && !AddonManager.addons.contains(owner))
                        return;
                    try {
                        retry.run();
                    } catch(Loading l2) {
                        register(l2, owner, retry, depth + 1, maxRetries);
                    } catch(RuntimeException e) {
                        AddonManager.log("Resolve retry error: " + e);
                    }
                });
            }, (Consumer<Waitable.Waiting>)(w -> {
                handle[0] = w;
                if(owner != null)
                    owner.waitings.add(w);
            }));
        } catch(Loading.UnwaitableEvent e) {
            // Expected/benign, and routine (D-092's boundary -- e.g. an equipped item's sprite still
            // building, hit on every fast gear swap): silent by design, unlike the rarer MAX_RETRIES
            // give-up above (still logDiag'd) or the retry-error catch below (a real bug, still log'd).
        }
    }
}
