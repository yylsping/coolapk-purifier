package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OBSERVE_ONLY contract: the host decision method always runs and its result
 * is returned unchanged, no matter the module configuration. No true→false,
 * no false→true, no short-circuit, no result replacement, ever.
 */
public final class SplashDecisionPolicyTest {
    @Test
    public void originalTrueIsReturnedTrueWithObservationAfterProceed() throws Throwable {
        List<String> order = new ArrayList<>();
        List<Object> observed = new ArrayList<>();
        Object result = SplashDecisionPolicy.intercept(() -> {
            order.add("original");
            return true;
        }, (original, returned) -> {
            order.add("observe");
            observed.addAll(Arrays.asList(original, returned));
        });

        assertEquals(true, result);
        assertEquals(Arrays.asList("original", "observe"), order);
        assertEquals(Arrays.asList(true, true), observed);
    }

    @Test
    public void originalFalseIsReturnedFalse() throws Throwable {
        List<Object> observed = new ArrayList<>();
        Object result = SplashDecisionPolicy.intercept(() -> false,
                (original, returned) -> observed.addAll(Arrays.asList(original, returned)));

        assertEquals(false, result);
        assertEquals(Arrays.asList(false, false), observed);
    }

    @Test
    public void everyOriginalValueRoundTripsUnchangedAndExactlyOnce() throws Throwable {
        for (Boolean original : new Boolean[]{true, false}) {
            AtomicInteger calls = new AtomicInteger();
            assertSame(original, SplashDecisionPolicy.intercept(() -> {
                calls.incrementAndGet();
                return original;
            }, (a, b) -> { }));
            assertEquals(1, calls.get());
        }
    }

    @Test
    public void hostExceptionPropagatesAndSkipsObservation() {
        Throwable hostFailure = new IllegalStateException("host");
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicInteger observations = new AtomicInteger();
        try {
            SplashDecisionPolicy.intercept(() -> {
                originalCalls.incrementAndGet();
                throw hostFailure;
            }, (a, b) -> observations.incrementAndGet());
            fail("host exception lost");
        } catch (Throwable actual) {
            assertSame(hostFailure, actual);
        }
        assertEquals(1, originalCalls.get());
        assertEquals(0, observations.get());
    }

    @Test
    public void observationFailureCannotChangeResultOrReplayHost() throws Throwable {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(true, SplashDecisionPolicy.intercept(() -> {
            calls.incrementAndGet();
            return true;
        }, (a, b) -> {
            throw new IllegalStateException("diagnostic");
        }));
        assertEquals(1, calls.get());
        assertEquals(false, SplashDecisionPolicy.intercept(() -> false, (a, b) -> {
            throw new IllegalStateException("diagnostic");
        }));
    }
}
