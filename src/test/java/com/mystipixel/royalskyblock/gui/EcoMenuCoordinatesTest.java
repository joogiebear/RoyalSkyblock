package com.mystipixel.royalskyblock.gui;

import com.willfp.eco.util.MenuUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The menu port's one silent failure mode.
 *
 * <p>{@code MenuSlot} stores a 0-based inventory index; eco's {@code setSlot} takes a 1-based
 * row/column. If the conversion is off by one, nothing throws and no test of "does the menu open"
 * fails — every button simply renders in the wrong place, and on a live server that is a broken UI
 * with no error to search for. So the conversion is asserted against eco's own
 * {@link MenuUtils#rowColumnToSlot} rather than a formula copied into the test, which would just
 * repeat any mistake made in the source.
 */
class EcoMenuCoordinatesTest {

    /** Every slot of a full six-row menu must survive index -> row/column -> index unchanged. */
    @Test
    void roundTripsEverySlotOfASixRowMenu() {
        for (int index = 0; index < 6 * 9; index++) {
            int roundTripped = MenuUtils.rowColumnToSlot(
                    EcoMenuFactory.row(index), EcoMenuFactory.column(index));
            assertEquals(index, roundTripped, "slot " + index + " did not round-trip");
        }
    }

    /** Pin the corners explicitly, so a failure says which end is wrong rather than just "index 0". */
    @Test
    void mapsTheCorners() {
        assertEquals(1, EcoMenuFactory.row(0), "first slot is row 1");
        assertEquals(1, EcoMenuFactory.column(0), "first slot is column 1");

        assertEquals(1, EcoMenuFactory.row(8), "slot 8 is still row 1");
        assertEquals(9, EcoMenuFactory.column(8), "slot 8 is the last column");

        assertEquals(2, EcoMenuFactory.row(9), "slot 9 wraps to row 2");
        assertEquals(1, EcoMenuFactory.column(9), "slot 9 wraps to column 1");

        assertEquals(6, EcoMenuFactory.row(53), "last slot of a six-row menu is row 6");
        assertEquals(9, EcoMenuFactory.column(53), "last slot of a six-row menu is column 9");
    }
}
