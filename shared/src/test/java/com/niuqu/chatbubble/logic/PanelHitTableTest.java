package com.niuqu.chatbubble.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ordered panel hit table that the group browser and context menus build.
 * The load-bearing property is "first added wins", which is how a screen controls
 * which overlapping region owns a click (e.g. a button drawn over a row).
 */
class PanelHitTableTest {

    private static final int JOIN = 101;
    private static final int LEAVE = 102;
    private static final int CREATE = 103;

    @Test void emptyTableHitsNothing() {
        PanelHitTable t = new PanelHitTable();
        assertEquals(PanelHitTable.NONE, t.hit(10, 10));
        assertTrue(t.isEmpty());
    }

    @Test void hitIsInclusiveOnAllEdges() {
        PanelHitTable t = new PanelHitTable();
        t.add(4, 10, 20, 30, JOIN);
        assertEquals(JOIN, t.hit(4, 10));
        assertEquals(JOIN, t.hit(20, 30));
        assertEquals(JOIN, t.hit(4, 30));
        assertEquals(JOIN, t.hit(20, 10));
        assertEquals(JOIN, t.hit(12, 20));
        assertEquals(PanelHitTable.NONE, t.hit(3, 20));
        assertEquals(PanelHitTable.NONE, t.hit(21, 20));
        assertEquals(PanelHitTable.NONE, t.hit(12, 9));
        assertEquals(PanelHitTable.NONE, t.hit(12, 31));
    }

    @Test void firstAddedEntryWinsOnOverlap() {
        PanelHitTable t = new PanelHitTable();
        // a row covers the whole band; the create button is drawn over its right end
        t.add(0, 0, 100, 20, JOIN);
        t.add(70, 0, 100, 20, CREATE);
        assertEquals(JOIN, t.hit(10, 10), "left of the button -> row");
        assertEquals(JOIN, t.hit(80, 10), "row added first still shadows the button");
    }

    @Test void laterEntryWinsWhenAddedFirst() {
        PanelHitTable t = new PanelHitTable();
        t.add(70, 0, 100, 20, CREATE);
        t.add(0, 0, 100, 20, JOIN);
        assertEquals(CREATE, t.hit(80, 10), "button owns its band when it is checked first");
        assertEquals(JOIN, t.hit(10, 10));
    }

    @Test void distinctRegionsRouteToTheirAction() {
        PanelHitTable t = new PanelHitTable();
        t.add(0, 0, 50, 16, JOIN);
        t.add(0, 18, 50, 34, LEAVE);
        assertEquals(JOIN, t.hit(25, 8));
        assertEquals(LEAVE, t.hit(25, 26));
        assertEquals(PanelHitTable.NONE, t.hit(25, 17), "gap between rows");
    }

    @Test void clearDropsEveryEntry() {
        PanelHitTable t = new PanelHitTable();
        t.add(0, 0, 10, 10, JOIN);
        t.clear();
        assertEquals(0, t.size());
        assertEquals(PanelHitTable.NONE, t.hit(5, 5));
        assertFalse(t.contains(5, 5));
    }

    @Test void sizeCountsEntries() {
        PanelHitTable t = new PanelHitTable();
        t.add(0, 0, 1, 1, JOIN);
        t.add(0, 2, 1, 3, LEAVE);
        assertEquals(2, t.size());
        assertTrue(t.contains(0, 0));
    }
}
