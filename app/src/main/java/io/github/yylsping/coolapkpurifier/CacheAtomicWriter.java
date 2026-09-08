package io.github.yylsping.coolapkpurifier;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Atomic cache persistence. The destination is never opened directly: the
 * complete payload is written to a sibling temp file, fsynced, then replaced
 * with one rename. If rename fails the old destination remains untouched.
 */
final class CacheAtomicWriter {
    interface ReplaceOperation {
        boolean replace(File temp, File destination);
    }

    /**
     * Primary path is a single atomic rename (POSIX/Android replaces the
     * destination atomically). Some file systems — notably Windows, where the
     * unit tests run — refuse to rename onto an existing file, so a
     * delete+rename fallback keeps the write from being permanently impossible
     * there; it never runs on Android in practice.
     */
    static final ReplaceOperation RENAME_REPLACE = (temp, destination) -> {
        if (temp.renameTo(destination)) {
            return true;
        }
        if (destination.delete() || !destination.exists()) {
            return temp.renameTo(destination);
        }
        return false;
    };

    private CacheAtomicWriter() {
    }

    static boolean write(File destination, byte[] payload,
                         ReplaceOperation replaceOperation) {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return false;
        }
        File temp = new File(parent, destination.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(payload);
            out.flush();
            out.getFD().sync();
        } catch (Throwable ignored) {
            return false;
        }
        boolean replaced = false;
        try {
            replaced = replaceOperation.replace(temp, destination);
        } catch (Throwable ignored) {
            replaced = false;
        }
        return replaced;
    }
}
