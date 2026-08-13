package com.bettershulker.client.render;

import net.minecraft.client.gui.Font;

/** Text fitting shared by the tooltip's name badges and the Modern card's tabs. */
final class TooltipText {

    private TooltipText() {}

    /** Trims {@code text} to {@code maxWidth}, ending it in an ellipsis when it does not fit. */
    static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int allowed = Math.max(0, maxWidth - font.width(ellipsis));
        String result = text;
        while (!result.isEmpty() && font.width(result) > allowed) {
            result = result.substring(0, result.length() - 1);
        }
        return result + ellipsis;
    }
}
