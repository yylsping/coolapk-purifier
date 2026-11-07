package io.github.yylsping.coolapkpurifier;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class BottomNavigationSelectionTest {
    @Test public void everyAllowedCombinationPreservesSourceIdsAndHasARealInitialPage() {
        int tested = 0;
        for (int mask = 0; mask <= 31; mask++) {
            if (!BottomNavigationSelection.validMask(mask)) continue;
            tested++;
            BottomNavigationSelection selection = new BottomNavigationSelection(mask);
            assertNotEquals(2, selection.firstPage());
            assertTrue(selection.visualIndex(selection.firstPage()) >= 0);
            int previous = -1;
            for (int visual = 0; visual < selection.sources.size(); visual++) {
                int source = selection.sourceIndex(visual);
                assertTrue(source > previous);
                assertEquals(0, mask & (1 << source));
                assertEquals(visual, selection.visualIndex(source));
                previous = source;
            }
            for (int source = 0; source < 5; source++) {
                assertEquals((mask & (1 << source)) != 0, selection.visualIndex(source) == -1);
            }
        }
        assertEquals(30, tested);
    }

    @Test public void issueExampleKeepsOriginalMyPageIdFour() {
        BottomNavigationSelection selection = new BottomNavigationSelection(14);
        assertEquals(Arrays.asList(0, 4), selection.sources);
        assertEquals(4, selection.sourceIndex(1));
        assertEquals(-1, selection.visualIndex(2));
    }

    @Test public void leadingPublishButtonIsNeverAnInitialPage() {
        BottomNavigationSelection selection = new BottomNavigationSelection(3);
        assertEquals(Arrays.asList(2, 3, 4), selection.sources);
        assertEquals(3, selection.firstPage());
        assertEquals(0, selection.visualIndex(2));
    }

    @Test public void rejectsNoPagesUnknownBitsAndNegativeMasks() {
        assertFalse(BottomNavigationSelection.validMask(27));
        assertFalse(BottomNavigationSelection.validMask(31));
        assertFalse(BottomNavigationSelection.validMask(32));
        assertFalse(BottomNavigationSelection.validMask(-1));
    }

    @Test public void projectedListRetainsNativeItemsWithoutModifyingSource() {
        var source = Arrays.asList(new Object(), new Object(), new Object(), new Object(), new Object());
        var projected = new BottomNavigationHooks.ProjectedItems(source, new BottomNavigationSelection(14));
        assertEquals(2, projected.size());
        assertSame(source.get(0), projected.get(0));
        assertSame(source.get(4), projected.get(1));
        assertEquals(5, source.size());
        try { projected.add(new Object()); fail(); } catch (UnsupportedOperationException expected) { }
    }
}
