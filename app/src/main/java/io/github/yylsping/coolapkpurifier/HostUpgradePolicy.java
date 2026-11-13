package io.github.yylsping.coolapkpurifier;

import java.util.ArrayList;
import java.util.List;

/** Select only the host package; never change another app or the caller's list. */
final class HostUpgradePolicy {
    private static final String HOST = "com.coolapk.market";

    interface PackageReader {
        String read(Object value) throws ReflectiveOperationException;
    }

    static boolean isHost(String packageName) {
        return HOST.equals(packageName);
    }

    static List<?> withoutHost(List<?> input, PackageReader reader)
            throws ReflectiveOperationException {
        if (input == null) return null;
        ArrayList<Object> filtered = null;
        for (int i = 0; i < input.size(); i++) {
            Object item = input.get(i);
            if (isHost(reader.read(item))) {
                if (filtered == null) filtered = new ArrayList<>(input.subList(0, i));
            } else if (filtered != null) {
                filtered.add(item);
            }
        }
        return filtered == null ? input : filtered;
    }

    private HostUpgradePolicy() { }
}
