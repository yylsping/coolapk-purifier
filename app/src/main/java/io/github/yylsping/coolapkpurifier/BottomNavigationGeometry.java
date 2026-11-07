package io.github.yylsping.coolapkpurifier;

import java.util.ArrayList;
import java.util.List;

/** Extends the native five-slot formula to smaller bars which retain publishing. */
final class BottomNavigationGeometry {
    final List<Float> lefts = new ArrayList<>();
    final List<Float> widths = new ArrayList<>();
    final float tabWidth;

    BottomNavigationGeometry(float contentWidth, float padding, float reservedWidth,
                             float groupGap, float tabGap, int count, int post) {
        if (count < 2 || count > 5 || post < 0 || post >= count) throw new IllegalArgumentException("slots");
        int postGaps = (post > 0 ? 1 : 0) + (post < count - 1 ? 1 : 0);
        int tabGaps = count - 1 - postGaps;
        tabWidth = (contentWidth - reservedWidth - postGaps * groupGap - tabGaps * tabGap) / (count - 1);
        if (!Float.isFinite(tabWidth) || tabWidth <= 0 || reservedWidth <= 0) throw new IllegalArgumentException("width");
        float left = padding;
        for (int i = 0; i < count; i++) {
            float width = i == post ? reservedWidth : tabWidth;
            lefts.add(left);
            widths.add(width);
            left += width;
            if (i < count - 1) left += i == post || i + 1 == post ? groupGap : tabGap;
        }
    }
}
