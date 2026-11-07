package io.github.yylsping.coolapkpurifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Display projection only: host page ids and its five-page navigation remain intact. */
final class BottomNavigationSelection {
    enum Item {
        HOME("首页", 0, true),
        DIGITAL("数码", 1, true),
        POST("发布", 2, false),
        DISCOVERY("发现", 3, true),
        MINE("我的", 4, true);

        final String title;
        final int sourceIndex;
        final boolean page;
        Item(String title, int sourceIndex, boolean page) {
            this.title = title;
            this.sourceIndex = sourceIndex;
            this.page = page;
        }
        int bit() { return 1 << sourceIndex; }
    }

    static final int ALL_BITS = 31;
    static final int PAGE_BITS = 27;
    final int hiddenMask;
    final List<Integer> sources;

    BottomNavigationSelection(int mask) {
        if (!validMask(mask)) throw new IllegalArgumentException("at least one page is required");
        hiddenMask = mask;
        List<Integer> result = new ArrayList<>();
        for (Item item : Item.values()) if (!hidden(item)) result.add(item.sourceIndex);
        sources = Collections.unmodifiableList(result);
    }

    static boolean validMask(int mask) {
        return mask >= 0 && (mask & ~ALL_BITS) == 0 && (mask & PAGE_BITS) != PAGE_BITS;
    }

    boolean hidden(Item item) { return (hiddenMask & item.bit()) != 0; }
    int visualIndex(int source) { return sources.indexOf(source); }
    int sourceIndex(int visual) { return sources.get(visual); }
    int firstPage() {
        for (Item item : Item.values()) if (item.page && !hidden(item)) return item.sourceIndex;
        throw new IllegalStateException("no visible page");
    }
}
