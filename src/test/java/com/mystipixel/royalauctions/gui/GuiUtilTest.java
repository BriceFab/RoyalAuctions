package com.mystipixel.royalauctions.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

// The sale tax rate on the confirm screen.
class GuiUtilTest {

    @Test void percentKeepsTheDigitsARateNeeds() {
        assertEquals("10", GuiUtil.percent(0.1));
        assertEquals("7.5", GuiUtil.percent(0.075));
        assertEquals("0.001", GuiUtil.percent(0.00001), "a small positive rate never shows as 0");
        assertEquals("100", GuiUtil.percent(1));
        assertEquals("0", GuiUtil.percent(0));
    }
}
