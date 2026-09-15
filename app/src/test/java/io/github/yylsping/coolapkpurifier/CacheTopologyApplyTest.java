package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Release-hardening §2: a feature disabled at process start must not get its
 * hooks from a cache hit. These tests drive the real cache (file IO), the real
 * topology filter and the real apply loop; only the final hook sink is faked.
 */
public final class CacheTopologyApplyTest {
    private static final String STRING = "Ljava/lang/String;";

    private static ResolutionCache newCache() {
        return new ResolutionCache(new ModuleStorage.MemoryBlobStore(
                "testRemote", new HostDataMutationGuard()));
    }

    private static Map<String, ResolvedTarget> fullDynamicTargets() {
        Map<String, ResolvedTarget> targets = new LinkedHashMap<>();
        targets.put(TargetResolver.KEY_SPLASH_BASE,
                new ResolvedTarget(TargetResolver.KEY_SPLASH_BASE, "dexkit", STRING, ""));
        targets.put(TargetResolver.KEY_SPLASH_DECISION,
                new ResolvedTarget(TargetResolver.KEY_SPLASH_DECISION, "dexkit", STRING,
                        STRING + "->isEmpty()Z"));
        targets.put(TargetResolver.KEY_FEED,
                new ResolvedTarget(TargetResolver.KEY_FEED, "dexkit", STRING,
                        STRING + "->length()I"));
        targets.put("feed#2",
                new ResolvedTarget("feed#2", "dexkit", STRING, STRING + "->hashCode()I"));
        targets.put(TargetResolver.KEY_GETTER_TEMPLATE,
                new ResolvedTarget(TargetResolver.KEY_GETTER_TEMPLATE, "dexkit", STRING,
                        STRING + "->toString()Ljava/lang/String;"));
        targets.put(TargetResolver.KEY_GETTER_ENTITY_ID,
                new ResolvedTarget(TargetResolver.KEY_GETTER_ENTITY_ID, "dexkit", STRING,
                        STRING + "->hashCode()I"));
        return targets;
    }

    private static HookTopology topology(boolean splash, boolean feed) {
        EnumMap<PurifierConfig.Feature, Boolean> snapshot =
                new EnumMap<>(PurifierConfig.Feature.class);
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            snapshot.put(feature, Boolean.TRUE);
        }
        snapshot.put(PurifierConfig.Feature.SPLASH, splash);
        snapshot.put(PurifierConfig.Feature.FEED_SPONSOR, feed);
        return new HookTopology(snapshot, true);
    }

    private static TargetIdentity identity() throws Exception {
        org.json.JSONObject json = new org.json.JSONObject();
        json.put("package", "com.coolapk.market");
        json.put("apkPath", "/data/app/~~x/base.apk");
        json.put("apkSize", 123L);
        json.put("signingHash", "sha256:abc");
        json.put("token", "token-1");
        json.put("versionCode", 2608212L);
        json.put("versionName", "16.6.1");
        return TargetIdentity.fromJson(json);
    }

    private static final class FakeSink implements DynamicTargetApplier.Sink {
        Map<String, ResolvedTarget> accessorsMap;
        int feedInstalled;
        int gateClasses;
        int splashInstalled;
        int splashDecisionInstalled;

        @Override
        public void updateAccessors(Map<String, ResolvedTarget> merged, ClassLoader loader) {
            accessorsMap = merged;
        }

        @Override
        public boolean installFeed(Method method, ResolvedTarget target) {
            feedInstalled++;
            return true;
        }

        @Override
        public void addSplashGateClass(Class<?> type) {
            gateClasses++;
        }

        @Override
        public boolean installSplash(Class<?> type, ResolvedTarget target) {
            splashInstalled++;
            return true;
        }

        @Override
        public boolean installSplashDecision(Method method, ResolvedTarget target) {
            splashDecisionInstalled++;
            return true;
        }
    }

    /** Real cache write + lookup, then filter + apply, mirroring runSession. */
    private FakeSink consumeCache(ResolutionCache cache, TargetIdentity id,
                                  HookTopology topology) throws Exception {
        ResolutionCache.CacheLookup lookup = cache.lookup(id);
        assertTrue("expected cache hit", lookup.isHit());
        Map<String, ResolvedTarget> applicable =
                TargetApplicability.filter(lookup.targets, topology);
        FakeSink sink = new FakeSink();
        if (!applicable.isEmpty()) {
            Map<String, ResolvedTarget> merged = new LinkedHashMap<>(applicable);
            DynamicTargetApplier.apply(applicable, merged, id.getClass().getClassLoader(),
                    sink, new ModuleLog(null), "cache");
        }
        return sink;
    }

    @Test
    public void a_bothEnabled_cacheHitAppliesEverything() throws Exception {
        ResolutionCache cache = newCache();
        TargetIdentity id = identity();
        cache.saveTargets(id, fullDynamicTargets());

        FakeSink sink = consumeCache(cache, id, topology(true, true));
        assertEquals(1, sink.splashInstalled);
        assertEquals(1, sink.splashDecisionInstalled);
        assertEquals(1, sink.gateClasses);
        assertEquals(2, sink.feedInstalled);
        assertTrue(sink.accessorsMap.containsKey(TargetResolver.KEY_GETTER_TEMPLATE));
        assertTrue(sink.accessorsMap.containsKey(TargetResolver.KEY_GETTER_ENTITY_ID));
    }

    @Test
    public void b_splashOffFeedOn_cacheHitInstallsNoSplashHook() throws Exception {
        ResolutionCache cache = newCache();
        TargetIdentity id = identity();
        cache.saveTargets(id, fullDynamicTargets());

        HookTopology topology = topology(false, true);
        FakeSink sink = consumeCache(cache, id, topology);
        assertEquals(0, sink.splashInstalled);
        assertEquals(0, sink.splashDecisionInstalled);
        assertEquals(0, sink.gateClasses);
        assertEquals(2, sink.feedInstalled);
        assertTrue(sink.accessorsMap.containsKey(TargetResolver.KEY_GETTER_TEMPLATE));
    }

    @Test
    public void c_splashOnFeedOff_cacheHitInstallsNoFeedHook() throws Exception {
        ResolutionCache cache = newCache();
        TargetIdentity id = identity();
        cache.saveTargets(id, fullDynamicTargets());

        FakeSink sink = consumeCache(cache, id, topology(true, false));
        assertEquals(1, sink.splashInstalled);
        assertEquals(1, sink.splashDecisionInstalled);
        assertEquals(0, sink.feedInstalled);
        // Getters serve only the feed pipeline; they must not flow either.
        assertFalse(sink.accessorsMap.containsKey(TargetResolver.KEY_GETTER_TEMPLATE));
        assertFalse(sink.accessorsMap.containsKey(TargetResolver.KEY_GETTER_ENTITY_ID));
    }

    @Test
    public void d_bothOff_cacheCannotResurrectAnyDynamicHook() throws Exception {
        ResolutionCache cache = newCache();
        TargetIdentity id = identity();
        cache.saveTargets(id, fullDynamicTargets());

        HookTopology topology = topology(false, false);
        // The coordinator never even starts the resolver pipeline here.
        assertFalse(topology.needsDynamicBootstrap());
        Map<String, ResolvedTarget> applicable =
                TargetApplicability.filter(
                        cache.lookup(id).targets, topology);
        assertTrue(applicable.isEmpty());
    }

    @Test
    public void mergeForSavePreservesDisabledFeatureEntries() {
        Map<String, ResolvedTarget> all = fullDynamicTargets();
        Map<String, ResolvedTarget> resolved = new LinkedHashMap<>();
        resolved.put(TargetResolver.KEY_FEED, all.get(TargetResolver.KEY_FEED));
        resolved.put(TargetResolver.KEY_GETTER_TEMPLATE,
                all.get(TargetResolver.KEY_GETTER_TEMPLATE));

        Map<String, ResolvedTarget> saved = TargetApplicability.mergeForSave(
                resolved, all, topology(false, true));
        // Splash was disabled at start: not re-resolved, but kept in cache.
        assertTrue(saved.containsKey(TargetResolver.KEY_SPLASH_BASE));
        assertTrue(saved.containsKey(TargetResolver.KEY_FEED));
        assertTrue(saved.containsKey(TargetResolver.KEY_GETTER_TEMPLATE));
        // "feed#2" was applicable but not re-resolved this session: dropped,
        // matching the previous whole-entry replace semantics for active features.
        assertFalse(saved.containsKey("feed#2"));
    }

    @Test
    public void mergeForSaveResolvedEntriesWinOverCache() {
        Map<String, ResolvedTarget> cached = fullDynamicTargets();
        Map<String, ResolvedTarget> resolved = new LinkedHashMap<>();
        ResolvedTarget fresher = new ResolvedTarget(TargetResolver.KEY_SPLASH_BASE,
                "dexkit", "Ljava/lang/StringBuilder;", "");
        resolved.put(TargetResolver.KEY_SPLASH_BASE, fresher);

        Map<String, ResolvedTarget> saved = TargetApplicability.mergeForSave(
                resolved, cached, topology(false, true));
        // Splash is inapplicable under this topology, but the key is already
        // owned by the current session's resolution: the fresh entry wins.
        assertEquals("Ljava/lang/StringBuilder;",
                saved.get(TargetResolver.KEY_SPLASH_BASE).classDescriptor);
    }
}
