package keystrokesmod.novoline.font;

import keystrokesmod.novoline.font.api.FontManager;
import keystrokesmod.novoline.font.api.FontRenderer;
import keystrokesmod.novoline.font.api.FontType;
import keystrokesmod.novoline.font.impl.SimpleFontManager;

/** Novoline's original font atlas renderer and bundled typefaces. */
public final class NovolineFonts {
    private static final FontManager FONT_MANAGER = SimpleFontManager.create();

    private NovolineFonts() {
    }

    public static FontRenderer sf(int size) {
        return FONT_MANAGER.font(FontType.SF, size);
    }

    public static FontRenderer thin(int size) {
        return FONT_MANAGER.font(FontType.SFTHIN, size);
    }

    public static FontRenderer bold(int size) {
        return FONT_MANAGER.font(FontType.SFBOLD, size);
    }

    public static FontRenderer icons(int size) {
        return FONT_MANAGER.font(FontType.ICONFONT, size);
    }

    public static FontRenderer oxide(int size) {
        return FONT_MANAGER.font(FontType.OXIDE, size);
    }
}
