package com.gathin.tvhome;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class SelectionLayoutTest {
    @Test public void threeRowsLeaveRoomForDockAtTopAndLibraryAtBottom() {
        assertEquals(232, SelectionLayout.libraryHeightPx(1080, 151, 241, 3, 2f));
    }
    @Test public void oneDockRowStillAllowsFullSelectionScroll() {
        assertEquals(712, SelectionLayout.libraryHeightPx(1080, 151, 241, 1, 2f));
    }
    @Test public void longListKeepsNormalFooterHeight() {
        assertEquals(168, SelectionLayout.libraryHeightPx(1080, 151, 241, 4, 2f));
    }
    @Test public void logicalScreenUsesItsOwnDensityAndRounding() {
        assertEquals(116, SelectionLayout.libraryHeightPx(540, 76, 121, 3, 1f));
    }
}
