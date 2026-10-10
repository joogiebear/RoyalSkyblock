package com.mystipixel.royalskyblock.gui;

import com.willfp.eco.util.MenuUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// MenuSlot stores a 0-based index and eco's setSlot takes 1-based row/column; an off-by-one throws
// nothing and just misplaces every button. Asserted against eco's own MenuUtils.rowColumnToSlot.
class EcoMenuCoordinatesTest {

    // every slot of a full six-row menu must survive index -> row/column -> index unchanged
    @Test
    void roundTripsEverySlotOfASixRowMenu() {
        for (int index = 0; index < 6 * 9; index++) {
            int roundTripped = MenuUtils.rowColumnToSlot(
                    EcoMenuFactory.row(index), EcoMenuFactory.column(index));
            assertEquals(index, roundTripped, "slot " + index + " did not round-trip");
        }
    }

    // pin the corners explicitly, so a failure says which end is wrong
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
