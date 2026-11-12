package com.maximiliankraft.mobileagent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatInsetsTest {
    @Test
    public void keyboardInsetMovesComposerAboveKeyboard() {
        assertEquals(304, ChatInsets.bottomPadding(16, 24, 288));
    }

    @Test
    public void navigationInsetKeepsComposerAboveNavigationBarWhenKeyboardIsHidden() {
        assertEquals(40, ChatInsets.bottomPadding(16, 24, 0));
    }

    @Test
    public void negativeInsetsDoNotReduceContentPadding() {
        assertEquals(16, ChatInsets.bottomPadding(16, -1, -1));
    }
}
