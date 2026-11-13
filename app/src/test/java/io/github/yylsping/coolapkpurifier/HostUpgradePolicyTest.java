package io.github.yylsping.coolapkpurifier;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class HostUpgradePolicyTest {
    private static String name(Object item) { return item instanceof String ? (String) item : null; }

    @Test public void mixedBatchRemovesOnlyHostWithoutChangingCallerList() throws Exception {
        List<?> original = Collections.unmodifiableList(Arrays.asList("com.example.one", "com.coolapk.market",
                null, "com.example.two", "com.coolapk.market"));
        List<?> filtered = HostUpgradePolicy.withoutHost(original, HostUpgradePolicyTest::name);
        assertEquals(Arrays.asList("com.example.one", null, "com.example.two"), filtered);
        assertEquals(5, original.size());
        assertNotSame(original, filtered);
    }

    @Test public void ordinaryAppsAndUnknownRowsKeepIdentity() throws Exception {
        Object unknown = new Object();
        List<?> original = Arrays.asList("com.example.one", unknown, "com.coolapk.market.beta");
        assertSame(original, HostUpgradePolicy.withoutHost(original, HostUpgradePolicyTest::name));
    }

    @Test public void selfOnlyBatchBecomesEmpty() throws Exception {
        assertTrue(HostUpgradePolicy.withoutHost(Collections.singletonList("com.coolapk.market"),
                HostUpgradePolicyTest::name).isEmpty());
    }

    @Test public void emptyAndNullBatchesArePreserved() throws Exception {
        List<?> empty = Collections.emptyList();
        assertSame(empty, HostUpgradePolicy.withoutHost(empty, HostUpgradePolicyTest::name));
        assertNull(HostUpgradePolicy.withoutHost(null, HostUpgradePolicyTest::name));
    }

    @Test public void unreadablePackageDoesNotPartlyChangeTheInput() {
        List<?> original = Arrays.asList("com.coolapk.market", "com.example.one");
        try {
            HostUpgradePolicy.withoutHost(original, value -> {
                if ("com.example.one".equals(value)) throw new ReflectiveOperationException("unreadable row");
                return name(value);
            });
            fail("must let the interceptor fall back to the complete original call");
        } catch (ReflectiveOperationException expected) {
            assertEquals(Arrays.asList("com.coolapk.market", "com.example.one"), original);
        }
    }

    @Test public void downloadMatchIsExactAndNullSafe() {
        assertTrue(HostUpgradePolicy.isHost("com.coolapk.market"));
        for (String name : new String[]{null, "", "com.coolapk.market.beta", "com.coolapk.market:worker",
                "COM.COOLAPK.MARKET", "com.example.one"}) assertFalse(HostUpgradePolicy.isHost(name));
    }

    static abstract class Target {
        public void instance(List<?> apps, boolean flag) { }
        public static void staticTarget(List<?> apps, boolean flag) { }
        public abstract void abstractTarget(List<?> apps, boolean flag);
        public native void nativeTarget(List<?> apps, boolean flag);
    }

    @Test public void hookContractsRejectStaticReturnAndImplementationDrift() throws Exception {
        assertEquals("instance", HostUpgradeHooks.Runtime.exact(Target.class, "instance", void.class,
                false, List.class, boolean.class).getName());
        for (String method : new String[]{"staticTarget", "abstractTarget", "nativeTarget"}) {
            try {
                HostUpgradeHooks.Runtime.exact(Target.class, method, void.class, false, List.class, boolean.class);
                fail(method);
            } catch (NoSuchMethodException expected) { }
        }
        try {
            HostUpgradeHooks.Runtime.exact(Target.class, "instance", boolean.class, false, List.class, boolean.class);
            fail("changed return type");
        } catch (NoSuchMethodException expected) { }
    }
}
