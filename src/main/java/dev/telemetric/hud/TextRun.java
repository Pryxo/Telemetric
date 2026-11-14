package dev.telemetric.hud;

/**
 * A piece of styled text as the server sent it.
 *
 * @param color ARGB, or 0 for "theme default"
 * @param font  font id ({@code "namespace:path"}), or null for the default font
 */
public record TextRun(String text, int color, String font) {}
