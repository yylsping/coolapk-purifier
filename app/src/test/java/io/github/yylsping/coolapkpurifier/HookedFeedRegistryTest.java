package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Coverage settling probes LIVE installed hooks by declaring-class
 * descriptor, so the registry must track the class of every hook that was
 * actually installed (a resolved-but-not-installable descriptor must not
 * count as coverage).
 */
public final class HookedFeedRegistryTest {
    public static final class AnchorA {
        public List<Object> first(List<Object> source, boolean flag) {
            return source;
        }

        public List<Object> second(List<Object> source, boolean flag) {
            return source;
        }
    }

    public static final class AnchorB {
        public List<Object> only(List<Object> source, boolean flag) {
            return source;
        }
    }

    @Test
    public void tracksDeclaringClassDescriptorsOfLiveHooks() throws Exception {
        HookedFeedRegistry registry = new HookedFeedRegistry();

        registry.add(AnchorA.class.getMethod("first", List.class, boolean.class));

        assertTrue(registry.hasHookedInClass(
                DescriptorUtils.classDescriptorOf(AnchorA.class)));
        assertFalse(registry.hasHookedInClass(
                DescriptorUtils.classDescriptorOf(AnchorB.class)));
        assertEquals(1, registry.size());
    }

    @Test
    public void severalMethodsOfOneClassCountOnceForCoverage() throws Exception {
        HookedFeedRegistry registry = new HookedFeedRegistry();
        registry.add(AnchorA.class.getMethod("first", List.class, boolean.class));
        registry.add(AnchorA.class.getMethod("second", List.class, boolean.class));

        assertEquals(2, registry.size());
        assertTrue(registry.hasHookedInClass(
                DescriptorUtils.classDescriptorOf(AnchorA.class)));
    }

    @Test
    public void duplicateAddsAreIgnored() throws Exception {
        HookedFeedRegistry registry = new HookedFeedRegistry();
        java.lang.reflect.Method method =
                AnchorA.class.getMethod("first", List.class, boolean.class);

        assertTrue(registry.add(method));
        assertFalse(registry.add(method));
        assertTrue(registry.contains(method));
        assertEquals(1, registry.size());
    }

    @Test
    public void nullAndUnknownDescriptorsAreRejected() {
        HookedFeedRegistry registry = new HookedFeedRegistry();

        assertFalse(registry.add(null));
        assertFalse(registry.contains(null));
        assertFalse(registry.hasHookedInClass(null));
        assertFalse(registry.hasHookedInClass("Lnever/Installed;"));
        assertEquals(0, registry.size());
    }

    @Test
    public void anchorSemanticsSettleOnlyWhenBothClassesAreLive() throws Exception {
        HookedFeedRegistry registry = new HookedFeedRegistry();
        registry.add(AnchorA.class.getMethod("first", List.class, boolean.class));

        String anchorA = DescriptorUtils.classDescriptorOf(AnchorA.class);
        String anchorB = DescriptorUtils.classDescriptorOf(AnchorB.class);

        assertFalse(ReadinessPolicy.isCoverageSettledByAnchors(
                registry.hasHookedInClass(anchorA), registry.hasHookedInClass(anchorB)));

        registry.add(AnchorB.class.getMethod("only", List.class, boolean.class));
        assertTrue(ReadinessPolicy.isCoverageSettledByAnchors(
                registry.hasHookedInClass(anchorA), registry.hasHookedInClass(anchorB)));
    }
}
