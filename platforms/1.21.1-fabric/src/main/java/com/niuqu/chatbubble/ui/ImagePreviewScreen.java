package com.niuqu.chatbubble.ui;

import com.niuqu.chatbubble.image.AnimatedImageLoader;
import com.niuqu.chatbubble.image.ImageEntry;
import com.niuqu.chatbubble.image.ImageLoader;
import com.niuqu.chatbubble.logic.ImageGeometry;
import com.niuqu.chatbubble.render.RoundRectRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/**
 * M8 (§13 D3) — full-screen image preview, opened by clicking an inline
 * thumbnail. The image is aspect-fitted to the screen (small images are scaled
 * up so they can be inspected), the mouse wheel zooms and dragging pans; the
 * framing maths live in the platform-free {@link ImageGeometry} so all three
 * targets lay the image out identically. Esc returns to the chat screen it was
 * opened over (keeping its scroll position), and a button still opens the URL in
 * the system browser — that used to be the only action of a click.
 */
public class ImagePreviewScreen extends Screen {

    private static final int MARGIN = 12;
    private static final int TOP_BAR_H = 22;

    private final Screen parent;
    private final String url;

    private float zoom = 1f;
    private int panX, panY;
    private boolean dragging;
    private double dragStartX, dragStartY;
    private int panStartX, panStartY;

    // Browser button, recomputed in init()
    private int btnX, btnY, btnW, btnH;

    public ImagePreviewScreen(Screen parent, String url) {
        super(Text.translatable("e33chat.image.preview.title"));
        this.parent = parent;
        this.url = url;
    }

    @Override
    protected void init() {
        String label = Text.translatable("e33chat.image.open_browser").getString();
        btnH = 16;
        btnW = textRenderer.getWidth(label) + 16;
        btnX = width - MARGIN - btnW;
        btnY = (TOP_BAR_H - btnH) / 2 + 2;
    }

    private record Tex(Identifier id, int w, int h) {}

    /** Mirrors the chat screen's animatedEntry: extension hint first, then a
     *  content probe for the extension-less server media transport. */
    private AnimatedImageLoader.Entry animatedProbe() {
        var a = AnimatedImageLoader.getOrLoad(url, null);
        if (a == null && url != null && url.startsWith("e33chat://media/")
                && !AnimatedImageLoader.definitivelyStill(url, null)) {
            a = AnimatedImageLoader.getOrLoadAny(url, null);
        }
        return a;
    }

    /** Resolved texture + source size, or null while loading / on failure. */
    private Tex resolve() {
        var animated = animatedProbe();
        if (animated != null) {
            if (animated.ready() && animated.width() > 0 && animated.height() > 0) {
                Identifier id = animated.texture();
                if (id != null) return new Tex(id, animated.width(), animated.height());
            }
            // Decoding and known-animated: don't let the still loader win the race.
            if (!animated.failed() && !animated.staticImage()) return null;
        }
        ImageEntry entry = ImageLoader.getOrLoad(url);
        if (entry != null && entry.state() == ImageEntry.State.LOADED
                && entry.textureId() != null && entry.width() > 0 && entry.height() > 0) {
            return new Tex(entry.textureId(), entry.width(), entry.height());
        }
        return null;
    }

    private boolean failed() {
        var animated = animatedProbe();
        if (animated != null && animated.failed()) return true;
        ImageEntry entry = ImageLoader.getOrLoad(url);
        return entry != null && entry.state() == ImageEntry.State.FAILED;
    }

    private boolean isBrowserUrl() {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xE6101114);

        Tex tex = resolve();
        if (tex != null) {
            ImageGeometry.Rect box = ImageGeometry.previewRect(tex.w(), tex.h(), width, height, MARGIN, zoom, panX, panY);
            g.drawTexture(tex.id(), box.x(), box.y(), box.w(), box.h(),
                0, 0, tex.w(), tex.h(), tex.w(), tex.h());
        } else {
            String msg = Text.translatable(failed()
                ? "e33chat.image.failed" : "e33chat.image.loading").getString();
            g.drawText(textRenderer, msg, (width - textRenderer.getWidth(msg)) / 2, height / 2, 0xFFAAAAAA, false);
        }

        // Top bar: URL (truncated) + secondary "open in browser" entry for http(s).
        g.fill(0, 0, width, TOP_BAR_H, 0x99000000);
        int maxUrlW = isBrowserUrl() ? btnX - 10 : width - 12;
        String shown = textRenderer.trimToWidth(url, Math.max(8, maxUrlW))
            + (textRenderer.getWidth(url) > maxUrlW ? "..." : "");
        g.drawText(textRenderer, shown, 6, (TOP_BAR_H - textRenderer.fontHeight) / 2, 0xFFDDDDDD, false);

        if (isBrowserUrl()) {
            boolean hover = over(mouseX, mouseY, btnX, btnY, btnW, btnH);
            RoundRectRenderer.fill(g, btnX, btnY, btnX + btnW, btnY + btnH, 4,
                hover ? 0xFF3A5FCD : 0xFF2C4A9E);
            String label = Text.translatable("e33chat.image.open_browser").getString();
            g.drawText(textRenderer, label,
                btnX + (btnW - textRenderer.getWidth(label)) / 2, btnY + 4, 0xFFFFFFFF, false);
        }

        String hint = Text.translatable("e33chat.image.preview.hint").getString();
        g.drawText(textRenderer, hint, (width - textRenderer.getWidth(hint)) / 2, height - 14, 0xFF9A9A9A, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (isBrowserUrl() && over(mouseX, mouseY, btnX, btnY, btnW, btnH)) {
                openInBrowser();
                return true;
            }
            Tex tex = resolve();
            if (tex != null) {
                ImageGeometry.Rect box = ImageGeometry.previewRect(
                    tex.w(), tex.h(), width, height, MARGIN, zoom, panX, panY);
                if (over(mouseX, mouseY, box.x(), box.y(), box.w(), box.h())) {
                    dragging = true;
                    dragStartX = mouseX;
                    dragStartY = mouseY;
                    panStartX = panX;
                    panStartY = panY;
                    return true;
                }
                // Click on the backdrop (outside the image) dismisses, WeChat-style.
                close();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            panX = panStartX + (int) (mouseX - dragStartX);
            panY = panStartY + (int) (mouseY - dragStartY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        float factor = (float) Math.pow(1.2, verticalAmount);
        zoom = ImageGeometry.clampZoom(zoom * factor);
        // Keep the pan point roughly under the cursor; render() re-clamps it.
        panX = Math.round(panX * factor);
        panY = Math.round(panY * factor);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void openInBrowser() {
        try {
            MinecraftClient.getInstance().keyboard.setClipboard(url);
        } catch (Throwable ignored) {}
        try {
            Util.getOperatingSystem().open(new java.net.URI(url));
        } catch (Throwable ignored) {}
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
