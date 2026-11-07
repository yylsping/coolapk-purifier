package io.github.yylsping.coolapkpurifier;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class HostPrivateBlobStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private HostPrivateBlobStore store(File directory) {
        HostPrivateBlobStore store = new HostPrivateBlobStore(
                ModuleStorageContract.CONFIG_KEY, new HostDataMutationGuard(), null);
        store.attach(directory);
        return store;
    }

    @Test public void navigationSelectionUsesAllowedAtomicHostStorageAndSurvivesReload() {
        HostPrivateBlobStore store = store(folder.getRoot());
        PurifierConfig config = new PurifierConfig(store, null, null);
        assertTrue(config.setBottomNavigationHidden(BottomNavigationSelection.Item.POST, true));
        assertTrue(config.setBottomNavigationHidden(BottomNavigationSelection.Item.DIGITAL, true));
        PurifierConfig loaded = new PurifierConfig(store(folder.getRoot()), null, null);
        assertEquals(6, loaded.hiddenBottomNavigationItems());
        assertEquals(3, store.persistentWriteCount());
        assertFalse(store.write(store.read(), "configBottomNavigation:UNKNOWN"));
        assertFalse(ModuleStorageContract.isValidWriteReason(ModuleStorageContract.CACHE_KEY,
                "configBottomNavigation:POST"));
        assertEquals(1, folder.getRoot().list().length);
    }

    @Test public void frameworkSelectionMigratesOnceAndHostFileWinsOnNextLoad() {
        ModuleStorage.MemoryBlobStore old = new ModuleStorage.MemoryBlobStore(
                "oldFramework", new HostDataMutationGuard());
        PurifierConfig oldConfig = new PurifierConfig(old, null, null);
        assertTrue(oldConfig.setEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS, true));
        AtomicInteger reads = new AtomicInteger();
        PurifierConfig.LegacySource legacy = new PurifierConfig.LegacySource() {
            public byte[] read() { reads.incrementAndGet(); return old.read(); }
            public String description() { return "oldFramework"; }
        };
        HostPrivateBlobStore first = store(folder.getRoot());
        PurifierConfig config = new PurifierConfig(first, legacy, null);
        assertEquals("legacyImportedToStore", config.loadedSource());
        assertTrue(config.isEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS));
        assertTrue(config.setEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS, false));
        PurifierConfig next = new PurifierConfig(store(folder.getRoot()), legacy, null);
        assertFalse(next.isEnabled(PurifierConfig.Feature.SEARCH_HOT_RANKINGS));
        assertEquals("storeAuthoritative", next.loadedSource());
        assertEquals(1, reads.get());
        assertTrue(new File(folder.getRoot(), PurifierConfig.FILE_NAME).isFile());
        assertEquals(1, folder.getRoot().list().length);
    }

    @Test public void inaccessibleDirectoryRejectsWriteAndRollsBackSelection() {
        HostPrivateBlobStore store = store(new File(folder.getRoot(), "missing/dir"));
        PurifierConfig config = new PurifierConfig(store, null, null);
        long revision = config.revision();
        assertFalse(config.setEnabled(PurifierConfig.Feature.AUTO_COMMENT, true));
        assertFalse(config.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertEquals(revision, config.revision());
        assertEquals(0, store.persistentWriteCount());
    }

    @Test public void corruptHostSnapshotDoesNotResurrectFrameworkSelection() throws Exception {
        File file = new File(folder.getRoot(), PurifierConfig.FILE_NAME);
        Files.write(file.toPath(), new byte[ModuleStorageContract.CONFIG_MAX_BYTES + 1]);
        HostPrivateBlobStore store = store(folder.getRoot());
        assertTrue(store.hasSnapshot());
        PurifierConfig.LegacySource forbidden = new PurifierConfig.LegacySource() {
            public byte[] read() { throw new AssertionError("must not read stale framework"); }
            public String description() { return "forbidden"; }
        };
        PurifierConfig config = new PurifierConfig(store, forbidden, null);
        assertEquals("storeInvalidDefaults", config.loadedSource());
        assertTrue(config.isEnabled(PurifierConfig.Feature.SPLASH));
        assertFalse(config.isEnabled(PurifierConfig.Feature.AUTO_COMMENT));
        assertEquals(1, store.persistentWriteCount());
        assertTrue(file.length() < ModuleStorageContract.CONFIG_MAX_BYTES);
    }

    @Test public void invalidWriteCannotDamageExistingSnapshot() {
        HostPrivateBlobStore store = store(folder.getRoot());
        PurifierConfig config = new PurifierConfig(store, null, null);
        byte[] prior = store.read();
        assertFalse(store.write(new byte[0], "configCreateDefaults"));
        assertFalse(store.write(new byte[ModuleStorageContract.CONFIG_MAX_BYTES + 1], "configCreateDefaults"));
        assertFalse(store.write(new byte[] {1}, "unrecognized"));
        assertArrayEquals(prior, store.read());
        assertEquals(1, store.persistentWriteCount());
    }
}
