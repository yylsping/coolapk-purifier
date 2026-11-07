package io.github.yylsping.coolapkpurifier;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Identity-based producer ownership, including lists still held by an older composition. */
final class BottomNavigationOwnedLists {
    private final List<WeakReference<List<?>>> lists = new ArrayList<>();

    synchronized void remember(List<?> list) {
        if (contains(list)) return;
        lists.add(new WeakReference<>(list));
    }

    synchronized boolean contains(List<?> list) {
        boolean found = false;
        for (Iterator<WeakReference<List<?>>> iterator = lists.iterator(); iterator.hasNext();) {
            List<?> known = iterator.next().get();
            if (known == null) iterator.remove();
            else if (known == list) found = true;
        }
        return found;
    }
}
