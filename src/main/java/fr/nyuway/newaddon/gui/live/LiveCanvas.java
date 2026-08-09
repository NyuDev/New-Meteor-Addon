package fr.nyuway.newaddon.gui.live;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import net.minecraft.client.Minecraft;

//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}

/**
 * The one place that knows how this Minecraft version draws.
 *
 * <h2>What actually differs</h2>
 * Less than it first appears. 26.x stopped drawing inside {@code render} and moved to
 * {@code Renderable.extractRenderState}, which looks like an architectural break - but the
 * object handed to it, {@code GuiGraphicsExtractor}, carries the same immediate-mode calls the
 * old {@code GuiGraphics} did. {@code fill} and the scissor pair are identical down to the
 * signature; only the text call was renamed from {@code drawString} to {@code text}.
 *
 * <p>So the whole divergence between nine versions and three is a type and a method name, and
 * it lives here rather than being sprinkled through fifteen hundred lines of window drawing.
 *
 * <h2>Colours</h2>
 * Everything takes ARGB. A colour with a zero alpha byte is invisible, which on this codebase
 * has already cost one round of "why is nothing drawing" - so {@link #opaque} exists to say
 * what was meant.
 */
public final class LiveCanvas {

    //? if <26.1 {
    private final GuiGraphics g;
    //?} else {
    /*private final GuiGraphicsExtractor g;
    *///?}

    private final Minecraft mc = Minecraft.getInstance();

    /** Skins seen while a player was online, reused when they later go offline this session. */
    private static final java.util.Map<java.util.UUID, Object> SKINS = new java.util.HashMap<>();

    //? if <26.1 {
    public LiveCanvas(GuiGraphics g) {
        this.g = g;
    }
    //?} else {
    /*public LiveCanvas(GuiGraphicsExtractor g) {
        this.g = g;
    }
    *///?}

    /**
     * Wraps whatever draw context Meteor hands to a {@link Render2DEvent}, so the HUD toast can
     * be drawn with the same primitives the windows use. The field it lives in is the only thing
     * that moved at 26.1: {@code drawContext} became {@code graphics}, one a {@code GuiGraphics}
     * and the other a {@code GuiGraphicsExtractor}, which is exactly the split this class exists
     * to hide.
     */
    public static LiveCanvas of(Render2DEvent event) {
        //? if <26.1 {
        return new LiveCanvas(event.drawContext);
        //?} else {
        /*return new LiveCanvas(event.graphics);
        *///?}
    }

    /** Filled rectangle, in ARGB. */
    public void rect(int left, int top, int right, int bottom, int argb) {
        g.fill(left, top, right, bottom, argb);
    }

    /** Rectangle given as position and size, which is how the window code thinks. */
    public void box(int x, int y, int width, int height, int argb) {
        g.fill(x, y, x + width, y + height, argb);
    }

    /** One-pixel outline. */
    public void outline(int x, int y, int width, int height, int argb) {
        box(x, y, width, 1, argb);
        box(x, y + height - 1, width, 1, argb);
        box(x, y, 1, height, argb);
        box(x + width - 1, y, 1, height, argb);
    }

    public void text(String s, int x, int y, int argb) {
        text(s, x, y, argb, true);
    }

    public void text(String s, int x, int y, int argb, boolean shadow) {
        //? if <26.1 {
        g.drawString(mc.font, s, x, y, argb, shadow);
        //?} else {
        /*g.text(mc.font, s, x, y, argb, shadow);
        *///?}
    }

    public int width(String s) {
        return mc.font.width(s);
    }

    public int lineHeight() {
        return mc.font.lineHeight;
    }

    /** Clips everything drawn until {@link #unclip()} to this rectangle. */
    public void clip(int x, int y, int width, int height) {
        g.enableScissor(x, y, x + width, y + height);
    }

    public void unclip() {
        g.disableScissor();
    }

    /**
     * Alpha-forces a colour.
     *
     * <p>Livemessage's palette is stored as plain RGB, so drawing it straight would ask for
     * fully transparent everything - the failure that looks exactly like a GUI that is not
     * running at all.
     */
    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    /** ARGB from a colour and an alpha in 0..1, for the fades the windows animate with. */
    public static int withAlpha(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    /**
     * Draws a player's head - face plus hat overlay - as a {@code size}-square icon, ringed green
     * when they are on the server and grey when they are not.
     *
     * <p>This is where the skin API's drift is absorbed, and it is more than one change.
     * {@code getSkinLocation} became {@code getSkin} at 1.20.2; the {@code PlayerSkin} it returns
     * carried a plain texture until 1.21.10 folded it into a {@code ClientAsset.Texture}; and
     * {@code PlayerFaceRenderer}, which does the face-and-hat blit for us on every obfuscated
     * build, was dropped in 26.x - so there the two 8x8 patches of a 64x64 skin are blitted by
     * hand, face at u=8 and hat at u=40, both v=8.
     *
     * <p>Offline players are not on the tab list, so their real skin is not directly to hand. A
     * skin seen while they were online this session is remembered and reused; failing that, the
     * default skin for their UUID stands in, so every peer shows a head rather than a hole.
     */
    public void head(java.util.UUID uuid, int x, int y, int size) {
        var connection = mc.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(uuid);
        boolean online = info != null;

        // Offline and never seen this session: their own skin, fetched from Mojang and kept.
        // Falling back to the default skin here was drawing somebody else's head - Steve's - on
        // a window whose whole point is which person it belongs to.
        Object fetched = online ? null : fr.nyuway.newaddon.utils.Skins.texture(uuid);

        //? if <1.20.2 {
        /*net.minecraft.resources.ResourceLocation skin;
        if (online) { skin = info.getSkinLocation(); SKINS.put(uuid, skin); }
        else if (fetched != null) { skin = (net.minecraft.resources.ResourceLocation) fetched; }
        else { Object seen = SKINS.get(uuid); skin = seen != null ? (net.minecraft.resources.ResourceLocation) seen : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(uuid); }
        net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, x, y, size, true, false);
        *///?} else if <1.21.3 {
        /*if (!online && fetched != null) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, (net.minecraft.resources.ResourceLocation) fetched, x, y, size, true, false);
        } else {
            net.minecraft.client.resources.PlayerSkin skin;
            if (online) { skin = info.getSkin(); SKINS.put(uuid, skin); }
            else { Object seen = SKINS.get(uuid); skin = seen != null ? (net.minecraft.client.resources.PlayerSkin) seen : net.minecraft.client.resources.DefaultPlayerSkin.get(uuid); }
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, x, y, size);
        }
        *///?} else if <1.21.10 {
        /*if (!online && fetched != null) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, (net.minecraft.resources.ResourceLocation) fetched, x, y, size, true, false, -1);
        } else {
            net.minecraft.client.resources.PlayerSkin skin;
            if (online) { skin = info.getSkin(); SKINS.put(uuid, skin); }
            else { Object seen = SKINS.get(uuid); skin = seen != null ? (net.minecraft.client.resources.PlayerSkin) seen : net.minecraft.client.resources.DefaultPlayerSkin.get(uuid); }
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, x, y, size);
        }
        *///?} else if <1.21.11 {
        /*if (!online && fetched != null) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, (net.minecraft.resources.ResourceLocation) fetched, x, y, size, true, false, -1);
        } else {
            net.minecraft.world.entity.player.PlayerSkin skin;
            if (online) { skin = info.getSkin(); SKINS.put(uuid, skin); }
            else { Object seen = SKINS.get(uuid); skin = seen != null ? (net.minecraft.world.entity.player.PlayerSkin) seen : net.minecraft.client.resources.DefaultPlayerSkin.get(uuid); }
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, x, y, size);
        }
        *///?} else if <26.1 {
        if (!online && fetched != null) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, (net.minecraft.resources.Identifier) fetched, x, y, size, true, false, -1);
        } else {
            net.minecraft.world.entity.player.PlayerSkin skin;
            if (online) { skin = info.getSkin(); SKINS.put(uuid, skin); }
            else { Object seen = SKINS.get(uuid); skin = seen != null ? (net.minecraft.world.entity.player.PlayerSkin) seen : net.minecraft.client.resources.DefaultPlayerSkin.get(uuid); }
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, skin, x, y, size);
        }
        //?} else {
        /*net.minecraft.resources.Identifier tex;
        if (!online && fetched != null) {
            tex = (net.minecraft.resources.Identifier) fetched;
        } else {
            net.minecraft.world.entity.player.PlayerSkin skin;
            if (online) { skin = info.getSkin(); SKINS.put(uuid, skin); }
            else { Object seen = SKINS.get(uuid); skin = seen != null ? (net.minecraft.world.entity.player.PlayerSkin) seen : net.minecraft.client.resources.DefaultPlayerSkin.get(uuid); }
            tex = skin.body().texturePath();
        }
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 8f, 8f, size, size, 8, 8, 64, 64, -1);
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex, x, y, 40f, 8f, size, size, 8, 8, 64, 64, -1);
        *///?}

        outline(x - 1, y - 1, size + 2, size + 2, opaque(online ? 0x50C873 : 0x6E6E6E));
    }
}
