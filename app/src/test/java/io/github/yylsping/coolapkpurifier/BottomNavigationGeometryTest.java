package io.github.yylsping.coolapkpurifier;

import org.junit.Test;
import static org.junit.Assert.*;

public class BottomNavigationGeometryTest {
    @Test public void everyPublishPositionFitsInsideTheOriginalContainer() {
        for (int count = 2; count <= 5; count++) for (int post = 0; post < count; post++) {
            BottomNavigationGeometry geometry = new BottomNavigationGeometry(1104, 14, 171.5f, 14, -28, count, post);
            assertEquals(count, geometry.lefts.size());
            assertEquals(14f, geometry.lefts.get(0), .001f);
            assertEquals(1118f, geometry.lefts.get(count - 1) + geometry.widths.get(count - 1), .001f);
            assertEquals(171.5f, geometry.widths.get(post), .001f);
            for (int i = 0; i < count; i++) {
                assertTrue(geometry.widths.get(i) > 0);
                assertTrue(geometry.lefts.get(i) >= 14);
                assertTrue(geometry.lefts.get(i) + geometry.widths.get(i) <= 1118.001f);
                if (i > 0) assertTrue(geometry.lefts.get(i) > geometry.lefts.get(i - 1));
            }
        }
    }

    @Test public void fiveSlotBaselineExactlyMatchesTheNativeFormula() {
        BottomNavigationGeometry geometry = new BottomNavigationGeometry(1104, 14, 171.5f, 14, -28, 5, 2);
        assertEquals((1104 - 171.5f - 2 * 14 - 2 * -28) / 4, geometry.tabWidth, 0);
    }
}
