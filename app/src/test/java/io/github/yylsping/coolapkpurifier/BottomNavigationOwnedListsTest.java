package io.github.yylsping.coolapkpurifier;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class BottomNavigationOwnedListsTest {
    @Test public void previousEmissionRemainsOwnedWhileCompositionStillUsesIt() {
        BottomNavigationOwnedLists ownership = new BottomNavigationOwnedLists();
        var previous = new ArrayList<>(Arrays.asList("old theme"));
        var current = new ArrayList<>(Arrays.asList("new theme"));
        ownership.remember(previous);
        ownership.remember(current);
        assertTrue(ownership.contains(previous));
        assertTrue(ownership.contains(current));
    }

    @Test public void structurallyEqualDemoDataDoesNotAcquireProducerOwnership() {
        BottomNavigationOwnedLists ownership = new BottomNavigationOwnedLists();
        var nativeData = new ArrayList<>(Arrays.asList("home", "my"));
        var demoData = new ArrayList<>(nativeData);
        ownership.remember(nativeData);
        ownership.remember(nativeData);
        assertTrue(ownership.contains(nativeData));
        assertFalse(ownership.contains(demoData));
    }
}
