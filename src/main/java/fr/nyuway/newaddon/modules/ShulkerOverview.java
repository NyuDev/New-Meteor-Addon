package fr.nyuway.newaddon.modules;

import fr.nyuway.newaddon.NewAddon;
import fr.nyuway.newaddon.utils.ShulkerContents;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * ShulkerOverview - what is in the box, drawn on the box.
 *
 * <p>Ported from BepHax. Twenty shulkers in a chest all look the same, and the only way to tell
 * which is which is to open each one. This draws the item there is most of on top of the box, so
 * a wall of storage reads at a glance, with a mark for the ones holding more than one thing.
 *
 * <h2>Read once per stack</h2>
 * Contents are worked out the first time a box is drawn and kept against the stack itself, in a
 * map that lets go when the stack does. Decoding a shulker's contents is not expensive once; it
 * is expensive sixty times a second for every slot on screen, which is what drawing means.
 */
public class ShulkerOverview extends Module {

    /** Where on the slot the badge sits. */
    public enum IconPosition {
        Center, TopLeft, TopRight, BottomLeft, BottomRight
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> iconSize = sgGeneral.add(new IntSetting.Builder()
        .name("icon-size")
        .description("Size of the item drawn on the box, in pixels. A slot is sixteen.")
        .defaultValue(12).min(4).max(16).sliderRange(4, 16)
        .build());

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Where on the slot to draw it.")
        .defaultValue(IconPosition.Center)
        .build());

    private final Setting<String> multipleText = sgGeneral.add(new StringSetting.Builder()
        .name("multiple-indicator")
        .description("Shown when the box holds more than one kind of thing, so a mixed box is " +
                     "not mistaken for a full one.")
        .defaultValue("+")
        .build());

    private final Setting<Integer> multipleSize = sgGeneral.add(new IntSetting.Builder()
        .name("multiple-size")
        .description("Size of that mark.")
        .defaultValue(8).min(4).max(16).sliderRange(4, 16)
        .build());

    private final Setting<Boolean> onlyInScreens = sgGeneral.add(new BoolSetting.Builder()
        .name("only-in-screens")
        .description("Draw only in inventories and chests, and leave the hotbar alone. The " +
                     "hotbar is small and you already know what is on it.")
        .defaultValue(false)
        .build());

    /**
     * Contents by stack, dropped when the stack is.
     *
     * <p>Keyed on the stack object rather than its contents: two boxes holding the same things
     * are still two boxes, and identity is what a weak map can let go of.
     */
    private final Map<ItemStack, ShulkerContents.Summary> cache = new WeakHashMap<>();

    public ShulkerOverview() {
        super(NewAddon.CATEGORY, "shulker-overview",
            "Draws what a shulker box holds on top of the box.");
    }

    private static ShulkerOverview get() {
        return Modules.get() == null ? null : Modules.get().get(ShulkerOverview.class);
    }

    /** Whether a shulker box drawn at this moment should carry its badge. */
    public static boolean active() {
        ShulkerOverview module = get();
        if (module == null || !module.isActive()) return false;
        return !module.onlyInScreens.get() || Minecraft.getInstance().screen != null;
    }

    /** Called for every item slot drawn; does nothing unless the stack is a shulker box. */
    //? if <26.1 {
    public static void render(net.minecraft.client.gui.GuiGraphics graphics,
                              ItemStack stack, int x, int y) {
    //?} else {
    /*public static void render(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                              ItemStack stack, int x, int y) {
    *///?}
        ShulkerOverview module = get();
        if (module == null || !active()) return;
        if (stack == null || stack.isEmpty() || !isShulker(stack)) return;

        ShulkerContents.Summary summary = module.cache.computeIfAbsent(stack,
            ShulkerContents::summarise);
        if (summary == null) return;

        module.draw(graphics, summary, x, y);
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block
            && block.getBlock() instanceof ShulkerBoxBlock;
    }

    //? if <26.1 {
    private void draw(net.minecraft.client.gui.GuiGraphics graphics,
                      ShulkerContents.Summary summary, int x, int y) {
    //?} else {
    /*private void draw(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                      ShulkerContents.Summary summary, int x, int y) {
    *///?}
        int size = iconSize.get();
        float scale = size / 16f;

        // Where the badge goes within the sixteen-pixel slot.
        int ox = switch (iconPosition.get()) {
            case Center -> (16 - size) / 2;
            case TopLeft, BottomLeft -> 0;
            case TopRight, BottomRight -> 16 - size;
        };
        int oy = switch (iconPosition.get()) {
            case Center -> (16 - size) / 2;
            case TopLeft, TopRight -> 0;
            case BottomLeft, BottomRight -> 16 - size;
        };

        ItemStack icon = new ItemStack(summary.dominant());

        // The pose stack became a 2D matrix stack at 1.21.8; the transform is the same either
        // way - move to the corner, shrink to the badge size, draw the item at the origin.
        //? if <1.21.8 {
        /*var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + ox, y + oy, 0);
        pose.scale(scale, scale, 1f);
        graphics.renderFakeItem(icon, 0, 0);
        pose.popPose();
        *///?} else if <26.1 {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + ox, y + oy);
        pose.scale(scale, scale);
        graphics.renderFakeItem(icon, 0, 0);
        pose.popMatrix();
        //?} else {
        /*var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + ox, y + oy);
        pose.scale(scale, scale);
        graphics.fakeItem(icon, 0, 0);
        pose.popMatrix();
        *///?}

        if (summary.types() <= 1) return;

        String mark = multipleText.get();
        if (mark == null || mark.isEmpty()) return;

        drawMark(graphics, mark, x, y);
    }

    /** The "more than one thing in here" mark, bottom-right of the slot and over everything. */
    //? if <26.1 {
    private void drawMark(net.minecraft.client.gui.GuiGraphics graphics, String mark, int x, int y) {
    //?} else {
    /*private void drawMark(net.minecraft.client.gui.GuiGraphicsExtractor graphics, String mark, int x, int y) {
    *///?}
        var font = Minecraft.getInstance().font;
        float scale = multipleSize.get() / 8f;

        //? if <1.21.8 {
        /*var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + 16 - font.width(mark) * scale, y + 16 - 8 * scale, 200);
        pose.scale(scale, scale, 1f);
        graphics.drawString(font, mark, 0, 0, 0xFFFFFF00, true);
        pose.popPose();
        *///?} else if <26.1 {
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + 16 - font.width(mark) * scale, y + 16 - 8 * scale);
        pose.scale(scale, scale);
        graphics.drawString(font, mark, 0, 0, 0xFFFFFF00, true);
        pose.popMatrix();
        //?} else {
        /*var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + 16 - font.width(mark) * scale, y + 16 - 8 * scale);
        pose.scale(scale, scale);
        graphics.text(font, mark, 0, 0, 0xFFFFFF00, true);
        pose.popMatrix();
        *///?}
    }
}
