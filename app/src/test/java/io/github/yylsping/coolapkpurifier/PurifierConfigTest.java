package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class PurifierConfigTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HostDataMutationGuard guard;
    private ModuleStorage.MemoryBlobStore store;

    @Before
    public void setUp() {
        guard = new HostDataMutationGuard();
        store = new ModuleStorage.MemoryBlobStore("testRemote", guard);
    }

    private PurifierConfig config() {
        return new PurifierConfig(store, missingLegacy(), null);
    }

    private static PurifierConfig.LegacySource missingLegacy() {
        return new PurifierConfig.LegacySource() {
            @Override public byte[] read() { return null; }
            @Override public String description() { return "missing"; }
        };
    }

    @Test public void navigationDefaultsPreserveBarAndRoundTripIndependentlyOfAds() {
        PurifierConfig config = config();
        assertEquals(0, config.hiddenBottomNavigationItems());
        assertTrue(config.setBottomNavigationHidden(BottomNavigationSelection.Item.DIGITAL, true));
        assertTrue(config.setBottomNavigationHidden(BottomNavigationSelection.Item.POST, true));
        assertTrue(config.setBottomNavigationHidden(BottomNavigationSelection.Item.DISCOVERY, true));
        PurifierConfig loaded = config();
        assertEquals(14, loaded.hiddenBottomNavigationItems());
        assertTrue(loaded.isEnabled(PurifierConfig.Feature.SPLASH));
        assertFalse(loaded.isEnabled(PurifierConfig.Feature.RELATED_DATA));
        assertTrue(loaded.hasNonDefaultSelections());
        assertTrue(loaded.setBottomNavigationHidden(BottomNavigationSelection.Item.POST, false));
        assertEquals(10, config().hiddenBottomNavigationItems());
    }

    @Test public void navigationLastPageGuardAndWriteFailureLeaveSnapshotUnchanged() {
        PurifierConfig config = config();
        for (BottomNavigationSelection.Item item : new BottomNavigationSelection.Item[]{
                BottomNavigationSelection.Item.HOME, BottomNavigationSelection.Item.DIGITAL,
                BottomNavigationSelection.Item.DISCOVERY}) assertTrue(config.setBottomNavigationHidden(item, true));
        byte[] before = store.read().clone();
        long revision = config.revision();
        assertFalse(config.setBottomNavigationHidden(BottomNavigationSelection.Item.MINE, true));
        assertEquals(revision, config.revision());
        assertArrayEquals(before, store.read());
        store.setFailWrites(true);
        assertFalse(config.setBottomNavigationHidden(BottomNavigationSelection.Item.POST, true));
        assertEquals(11, config.hiddenBottomNavigationItems());
        assertEquals(revision, config.revision());
        assertArrayEquals(before, store.read());
    }

    @Test public void oldSchemaTwoSelectionsDefaultNavigationToUnchanged() {
        store.seed("{\"schema\":2,\"options\":{\"remove_splash_ads\":false,\"remove_detail_sponsor\":true}}"
                .getBytes(StandardCharsets.UTF_8));
        PurifierConfig config = config();
        assertEquals(0, config.hiddenBottomNavigationItems());
        assertFalse(config.isEnabled(PurifierConfig.Feature.SPLASH));
        assertTrue(config.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));
    }

    @Test
    public void firstLoadPersistsLegacyProtectionsEnabledAndOptionsDisabled() {
        PurifierConfig config = config();

        assertTrue(config.isEnabled(PurifierConfig.Feature.SPLASH));
        assertTrue(config.isEnabled(PurifierConfig.Feature.FEED_SPONSOR));
        assertTrue(config.isEnabled(PurifierConfig.Feature.REPLY_SPONSOR));
        assertFalse(config.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertFalse(config.isEnabled(PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND));
        assertFalse(config.isEnabled(PurifierConfig.Feature.RELATED_DATA));
        assertFalse(config.isEnabled(PurifierConfig.Feature.SAME_TOPIC_FEED));
        assertFalse(config.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));
        assertEquals(PurifierConfig.PendingKind.DEFAULT, config.pendingKind());
        assertFalse(config.hasNonDefaultSelections());
        assertEquals(1, store.persistentWriteCount());
        assertEquals(0, guard.snapshot().hostPrivateWrites);

        PurifierConfig reloaded = config();
        assertEquals(PurifierConfig.PendingKind.DEFAULT, reloaded.pendingKind());
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.SPLASH));
        assertFalse(reloaded.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertEquals("storeAuthoritative", reloaded.loadedSource());
        assertEquals(1, store.persistentWriteCount());
    }

    @Test
    public void enablingIssueOptionIsImmediatelyDurableAndMarksSelectionPending() {
        PurifierConfig config = config();

        assertTrue(config.setEnabled(PurifierConfig.Feature.AUTO_COMMENT, true));
        assertEquals(PurifierConfig.PendingKind.SELECTION, config.pendingKind());
        assertTrue(config.hasNonDefaultSelections());

        PurifierConfig reloaded = config();
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertEquals(PurifierConfig.PendingKind.SELECTION, reloaded.pendingKind());
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    @Test
    public void issueOptionsAreIneffectiveBelowCoolapk15WithoutLosingChoice() {
        PurifierConfig config = config();
        config.setEnabled(PurifierConfig.Feature.DETAIL_SPONSOR, true);

        assertFalse(config.isEffectiveEnabled(PurifierConfig.Feature.DETAIL_SPONSOR, 14));
        assertTrue(config.isEffectiveEnabled(PurifierConfig.Feature.DETAIL_SPONSOR, 15));
        assertTrue(config.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));
    }

    @Test
    public void adaptationMarkerIsPersisted() {
        PurifierConfig config = config();
        assertTrue(config.markAdapted());

        PurifierConfig reloaded = config();
        assertEquals(PurifierConfig.PendingKind.NONE, reloaded.pendingKind());
    }

    @Test
    public void failedWriteRollsBackInMemorySelection() {
        PurifierConfig seed = config();
        assertFalse(seed.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));

        store.setFailWrites(true);
        PurifierConfig failing = config();
        assertFalse(failing.setEnabled(PurifierConfig.Feature.AUTO_COMMENT, true));
        assertFalse(failing.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    @Test
    public void disablingAnOptionAlsoMarksSelectionPending() {
        PurifierConfig config = config();
        assertTrue(config.isEnabled(PurifierConfig.Feature.REPLY_SPONSOR));
        assertTrue(config.markAdapted());

        assertTrue(config.setEnabled(PurifierConfig.Feature.REPLY_SPONSOR, false));
        assertEquals(PurifierConfig.PendingKind.SELECTION, config.pendingKind());
        PurifierConfig reloaded = config();
        assertFalse(reloaded.isEnabled(PurifierConfig.Feature.REPLY_SPONSOR));
        assertEquals(PurifierConfig.PendingKind.SELECTION, reloaded.pendingKind());
    }

    @Test
    public void multipleChangesSurviveRestart() {
        PurifierConfig config = config();
        assertTrue(config.setEnabled(PurifierConfig.Feature.SPLASH, false));
        assertTrue(config.setEnabled(PurifierConfig.Feature.AUTO_COMMENT, true));
        assertTrue(config.setEnabled(PurifierConfig.Feature.DETAIL_SPONSOR, true));

        PurifierConfig reloaded = config();
        assertFalse(reloaded.isEnabled(PurifierConfig.Feature.SPLASH));
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));
    }

    @Test
    public void malformedOrUnsupportedRemoteConfigFailsSafeToDefaults() {
        store.seed("{broken".getBytes(StandardCharsets.UTF_8));
        PurifierConfig malformed = config();
        assertTrue(malformed.isEnabled(PurifierConfig.Feature.REPLY_SPONSOR));
        assertFalse(malformed.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));

        store.seed("{\"schema\":999,\"options\":{}}"
                .getBytes(StandardCharsets.UTF_8));
        PurifierConfig unsupported = config();
        assertTrue(unsupported.isEnabled(PurifierConfig.Feature.FEED_SPONSOR));
        assertFalse(unsupported.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }

    @Test
    public void schemaOneRelatedDataKeyIsIgnoredAndRewrittenFalse() {
        store.seed(("{\"schema\":1,\"revision\":3,"
                + "\"pendingAdaptation\":\"none\",\"options\":{"
                + "\"remove_splash_ads\":false,"
                + "\"remove_related_data\":true,"
                + "\"remove_detail_sponsor\":true}}")
                .getBytes(StandardCharsets.UTF_8));

        PurifierConfig config = config();
        assertFalse(config.isEnabled(PurifierConfig.Feature.SPLASH));
        assertTrue(config.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));
        assertTrue(config.isEnabled(PurifierConfig.Feature.FEED_SPONSOR));
        assertFalse(config.isEnabled(PurifierConfig.Feature.RELATED_DATA));
        assertEquals("storeMigratedSchema1To2", config.loadedSource());

        String rewritten = new String(store.read(), StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("\"schema\":2"));
        assertTrue(rewritten.contains("\"remove_related_data\":false"));
        for (PurifierConfig.Feature feature : PurifierConfig.Feature.values()) {
            assertTrue(rewritten.contains(feature.key));
        }
    }

    @Test
    public void schemaTwoRelatedDataChoiceIsDurableWithoutChangingOldSeven() {
        PurifierConfig config = config();
        assertTrue(config.setEnabled(PurifierConfig.Feature.SPLASH, false));
        assertTrue(config.setEnabled(PurifierConfig.Feature.DETAIL_SPONSOR, true));
        assertTrue(config.setEnabled(PurifierConfig.Feature.RELATED_DATA, true));

        PurifierConfig reloaded = config();
        assertFalse(reloaded.isEnabled(PurifierConfig.Feature.SPLASH));
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.RELATED_DATA));
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.FEED_SPONSOR));
        assertFalse(reloaded.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
    }

    @Test
    public void schemaTwoSnapshotMissingSearchKeysFallsBackToDisabledDefaults() {
        // A 2.5.0-era schema-2 snapshot: only the original eight keys exist.
        store.seed(("{\"schema\":2,\"revision\":9,"
                + "\"pendingAdaptation\":\"none\",\"options\":{"
                + "\"remove_splash_ads\":false,"
                + "\"remove_feed_sponsor\":true,"
                + "\"remove_reply_sponsor\":false,"
                + "\"remove_auto_comment\":true,"
                + "\"remove_topic_device_recommend\":true,"
                + "\"remove_related_data\":true,"
                + "\"remove_same_topic_feed\":true,"
                + "\"remove_detail_sponsor\":true}}")
                .getBytes(StandardCharsets.UTF_8));

        PurifierConfig config = config();
        assertEquals("storeAuthoritative", config.loadedSource());
        // Missing search keys fall back to their defaults (off).
        assertFalse(config.isEnabled(PurifierConfig.Feature.SEARCH_HOT_WORDS));
        assertFalse(config.isEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS));
        // The original explicit values survive untouched.
        assertFalse(config.isEnabled(PurifierConfig.Feature.SPLASH));
        assertTrue(config.isEnabled(PurifierConfig.Feature.FEED_SPONSOR));
        assertFalse(config.isEnabled(PurifierConfig.Feature.REPLY_SPONSOR));
        assertTrue(config.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertTrue(config.isEnabled(PurifierConfig.Feature.TOPIC_DEVICE_RECOMMEND));
        assertTrue(config.isEnabled(PurifierConfig.Feature.RELATED_DATA));
        assertTrue(config.isEnabled(PurifierConfig.Feature.SAME_TOPIC_FEED));
        assertTrue(config.isEnabled(PurifierConfig.Feature.DETAIL_SPONSOR));
        assertEquals(9L, config.revision());
    }

    @Test
    public void searchFeatureSelectionsRoundTripThroughPersistence() {
        PurifierConfig config = config();
        assertFalse(config.isEnabled(PurifierConfig.Feature.SEARCH_HOT_WORDS));
        assertFalse(config.isEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS));

        assertTrue(config.setEnabled(PurifierConfig.Feature.SEARCH_HOT_WORDS, true));
        assertTrue(config.setEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS, true));

        PurifierConfig reloaded = config();
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.SEARCH_HOT_WORDS));
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS));
        // Untouched features keep their defaults.
        assertTrue(reloaded.isEnabled(PurifierConfig.Feature.SPLASH));
        assertFalse(reloaded.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));

        // The two gates are independent: toggling one never moves the other.
        assertTrue(reloaded.setEnabled(PurifierConfig.Feature.SEARCH_HOT_WORDS, false));
        PurifierConfig again = config();
        assertFalse(again.isEnabled(PurifierConfig.Feature.SEARCH_HOT_WORDS));
        assertTrue(again.isEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS));
    }

    @Test
    public void legacyFileImportIsReadOnlyAndRemoteBecomesAuthoritative() throws Exception {
        File legacy = folder.newFile(PurifierConfig.FILE_NAME);
        byte[] original = ("{\"schema\":1,\"revision\":15,"
                + "\"pendingAdaptation\":\"selection\",\"options\":{"
                + "\"remove_splash_ads\":false,"
                + "\"remove_feed_sponsor\":false,"
                + "\"remove_reply_sponsor\":false}}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(legacy.toPath(), original);
        long modified = legacy.lastModified();

        PurifierConfig imported = new PurifierConfig(store,
                new PurifierConfig.LegacyFileSource(legacy), null);

        assertEquals("legacyImportedToStore", imported.loadedSource());
        assertEquals(15L, imported.revision());
        assertFalse(imported.isEnabled(PurifierConfig.Feature.SPLASH));
        assertArrayEquals(original, Files.readAllBytes(legacy.toPath()));
        assertEquals(modified, legacy.lastModified());
        assertTrue(store.read().length > 0);
        assertEquals(0, guard.snapshot().hostPrivateWrites);
    }
}
