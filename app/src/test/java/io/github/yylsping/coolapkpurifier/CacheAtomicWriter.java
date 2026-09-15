package io.github.yylsping.coolapkpurifier;

import java.io.File;
import java.io.FileOutputStream;

/** Test-only fixture for the retired host-file atomic writer contract. */
final class CacheAtomicWriter {
    interface ReplaceOperation {
        boolean replace(File temp, File destination);
    }

    private CacheAtomicWriter() { }

    static boolean write(File destination, byte[] payload,
                         ReplaceOperation replaceOperation) {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return false;
        }
        File temp = new File(parent, destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(payload);
            output.flush();
            output.getFD().sync();
        } catch (Throwable ignored) {
            return false;
        }
        try {
            return replaceOperation.replace(temp, destination);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
