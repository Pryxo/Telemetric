package dev.telemetric.hud;

/** Small icon drawn in front of a HUD row. Plain data — {@code Render2D} knows how to draw both kinds. */
public sealed interface HudIcon {

    /**
     * A font glyph ("shortcut" character) from a resource pack, drawn as text in {@code font}.
     * Renders correctly only while the pack that defines it is loaded.
     *
     * @param font font id ({@code "namespace:path"}), or null for the default font
     */
    record Glyph(String text, String font) implements HudIcon {}

    /**
     * A PNG drawn one text line tall. {@code path} is a full id ({@code "namespace:path"}, e.g. a texture
     * from the server resource pack) or a bare path inside the mod's own {@code assets/telemetric/}.
     * Only the region {@code (u, v, regionW, regionH)} is drawn, so transparent padding can be trimmed
     * and the visible pixels line up with the pack glyphs.
     *
     * @param width  texture width in pixels
     * @param height texture height in pixels
     */
    record Texture(String path, int width, int height, int u, int v, int regionW, int regionH) implements HudIcon {
        /** Whole texture. */
        public Texture(String path, int width, int height) {
            this(path, width, height, 0, 0, width, height);
        }

        /** Same size/region, different source texture. */
        public Texture withPath(String newPath) {
            return new Texture(newPath, width, height, u, v, regionW, regionH);
        }
    }

    /**
     * An item drawn by its item model id ({@code "telos:material/boss/anubis"}), one text line tall — boss / drop
     * icons from the data tables. Needs the pack that defines the model.
     */
    record Item(String model) implements HudIcon {}
}
