package fr.nyuway.newaddon.gui;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Text box renderer that draws asterisks instead of the value, so a secret is not readable
 * over a shoulder, on a stream, or in a screenshot of the settings panel.
 *
 * <p>This hides the value on screen only. Meteor still stores the setting in plain text in
 * its config, as it does with every setting, so this is shoulder-surfing protection and not
 * secret storage.
 *
 * <p>Instantiated reflectively by Meteor, so it must keep a public no-arg constructor.
 */
public class PasswordRenderer implements WTextBox.Renderer {

    @Override
    public void render(GuiRenderer renderer, double x, double y, String text, Color color) {
        renderer.text("*".repeat(text.length()), x, y, color, false);
    }
}
