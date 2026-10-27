package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Темний HUD: перетягується за заголовок, закривається Right Shift або Esc. */
public class WebScreen extends Screen {
    private static final int W = 220;
    private static final int H = 210;
    private static final int TITLE_H = 18;
    private static final int PAD = 8;
    private static final int INNER_W = 204;
    private static final int ROW_H = 18;

    // відносні Y рядків
    private static final int Y_TOGGLE_WEB = 26;
    private static final int Y_SMOOTH = 48;       // підпис; сам повзунок на +8
    private static final int Y_REACH = 78;
    private static final int Y_RETURN = 108;
    private static final int Y_SEP = 132;
    private static final int Y_TOGGLE_DRAIN = 138;
    private static final int Y_DSMOOTH = 160;
    private static final int Y_CREDIT = 192;

    // кольори (ARGB)
    private static final int C_BORDER = 0xFF5BC0EB;
    private static final int C_BG = 0xF0141420;
    private static final int C_TITLE = 0xFF22223A;
    private static final int C_ON = 0xFF3FA044;
    private static final int C_ON_HOVER = 0xFF2E7D32;
    private static final int C_OFF = 0xFFD32F2F;
    private static final int C_OFF_HOVER = 0xFFB71C1C;
    private static final int C_BTN = 0xFF3A3A55;
    private static final int C_BTN_HOVER = 0xFF4A4A6A;
    private static final int C_TRACK = 0xFF2A2A40;
    private static final int C_TEXT = 0xFFDDDDDD;
    private static final int C_CREDIT = 0xFF9AA0B5;

    private boolean wasDown;
    private boolean shiftWasDown;
    private boolean closeRequested;
    private boolean dragging;
    private int dragOffX;
    private int dragOffY;
    private int sliderDragging = -1;

    public WebScreen() {
        super(Text.literal("WebOptimizer"));
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        // Right Shift ще затиснутий після відкриття меню - не закриваємось одразу
        this.shiftWasDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        if (closeRequested) {
            closeRequested = false;
            this.close();
        }
    }

    @Override
    public void removed() {
        WebConfig.save();
    }

    // ----- ввід (опитуємо GLFW напряму, як і було в оригіналі) -----

    private void pollInput(int mouseX, int mouseY) {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !wasDown) {
            onPress(mouseX, mouseY);
        }
        if (down) {
            onDrag(mouseX, mouseY);
        } else {
            dragging = false;
            sliderDragging = -1;
        }
        wasDown = down;

        boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (shift && !shiftWasDown) {
            closeRequested = true;
        }
        shiftWasDown = shift;
    }

    private static int sliderLabelY(int id) {
        return id == 0 ? Y_SMOOTH : id == 1 ? Y_REACH : Y_DSMOOTH;
    }

    private void onPress(int mx, int my) {
        WebConfig c = WebConfig.INSTANCE;
        int x = c.hudX;
        int y = c.hudY;

        if (inside(mx, my, x, y, W, TITLE_H)) {
            dragging = true;
            dragOffX = mx - x;
            dragOffY = my - y;
            return;
        }
        if (inside(mx, my, x + PAD, y + Y_TOGGLE_WEB, INNER_W, ROW_H)) {
            c.enabled = !c.enabled;
            WebConfig.save();
            return;
        }
        for (int id = 0; id < 3; id++) {
            if (inside(mx, my, x + PAD, y + sliderLabelY(id) + 8, INNER_W, ROW_H)) {
                sliderDragging = id;
                updateSlider(id, mx);
                return;
            }
        }
        if (inside(mx, my, x + PAD, y + Y_RETURN, INNER_W, ROW_H)) {
            c.returnSword = !c.returnSword;
            WebConfig.save();
            return;
        }
        if (inside(mx, my, x + PAD, y + Y_TOGGLE_DRAIN, INNER_W, ROW_H)) {
            c.autoDrain = !c.autoDrain;
            WebConfig.save();
        }
    }

    private void onDrag(int mx, int my) {
        WebConfig c = WebConfig.INSTANCE;
        if (dragging) {
            c.hudX = clamp(mx - dragOffX, 0, Math.max(0, this.width - W));
            c.hudY = clamp(my - dragOffY, 0, Math.max(0, this.height - H));
        }
        if (sliderDragging >= 0) {
            updateSlider(sliderDragging, mx);
        }
    }

    private void updateSlider(int id, int mx) {
        WebConfig c = WebConfig.INSTANCE;
        double frac = clamp((mx - (c.hudX + PAD)) / (double) INNER_W, 0.0, 1.0);
        if (id == 0) {
            c.smoothness = clamp((int) Math.round(frac * 1000.0), 0, 1000);
        } else if (id == 1) {
            double r = WebConfig.REACH_MIN + frac * (WebConfig.REACH_MAX - WebConfig.REACH_MIN);
            c.placeReach = Math.round(r * 10.0) / 10.0;      // крок 0.1 блока
        } else {
            c.drainSmoothness = clamp((int) Math.round(frac * 1000.0), 0, 1000);
        }
    }

    // ----- малювання -----

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        pollInput(mouseX, mouseY);
        WebConfig c = WebConfig.INSTANCE;
        int x = c.hudX;
        int y = c.hudY;

        ctx.fill(x - 1, y - 1, x + W + 1, y + H + 1, C_BORDER);
        ctx.fill(x, y, x + W, y + H, C_BG);
        ctx.fill(x, y, x + W, y + TITLE_H, C_TITLE);
        drawCentered(ctx, "WebOptimizer", x + W / 2, y + 5, C_BORDER);

        // павутинка
        drawToggle(ctx, x + PAD, y + Y_TOGGLE_WEB, "Павутинка: " + (c.enabled ? "ON" : "OFF"), c.enabled, mouseX, mouseY);
        drawSlider(ctx, x + PAD, y + Y_SMOOTH, "Плавність: " + c.smoothness + " / 1000", c.smoothness / 1000.0);
        double reachFrac = (c.placeReach - WebConfig.REACH_MIN) / (WebConfig.REACH_MAX - WebConfig.REACH_MIN);
        drawSlider(ctx, x + PAD, y + Y_REACH, "Дальність: " + String.format(java.util.Locale.ROOT, "%.1f", c.placeReach) + " бл.", reachFrac);
        drawButton(ctx, x + PAD, y + Y_RETURN, "Повертати меч: " + (c.returnSword ? "ON" : "OFF"), mouseX, mouseY);

        ctx.fill(x + PAD, y + Y_SEP, x + PAD + INNER_W, y + Y_SEP + 1, C_BTN);

        // Auto Drain
        drawToggle(ctx, x + PAD, y + Y_TOGGLE_DRAIN, "Auto Drain: " + (c.autoDrain ? "ON" : "OFF"), c.autoDrain, mouseX, mouseY);
        drawSlider(ctx, x + PAD, y + Y_DSMOOTH, "Плавність Drain: " + c.drainSmoothness + " / 1000", c.drainSmoothness / 1000.0);

        drawCentered(ctx, "By:GAB_DAB2025", x + W / 2, y + Y_CREDIT, C_CREDIT);
    }

    private void drawToggle(DrawContext ctx, int x, int y, String text, boolean on, int mx, int my) {
        boolean hover = inside(mx, my, x, y, INNER_W, ROW_H);
        int color = on ? (hover ? C_ON_HOVER : C_ON) : (hover ? C_OFF_HOVER : C_OFF);
        ctx.fill(x, y, x + INNER_W, y + ROW_H, color);
        drawCentered(ctx, text, x + INNER_W / 2, y + 5, 0xFFFFFFFF);
    }

    private void drawButton(DrawContext ctx, int x, int y, String text, int mx, int my) {
        boolean hover = inside(mx, my, x, y, INNER_W, ROW_H);
        ctx.fill(x, y, x + INNER_W, y + ROW_H, hover ? C_BTN_HOVER : C_BTN);
        drawCentered(ctx, text, x + INNER_W / 2, y + 5, C_TEXT);
    }

    /** labelY - Y підпису; повзунок малюється на 8 пікселів нижче. */
    private void drawSlider(DrawContext ctx, int x, int labelY, String label, double frac) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(label), x, labelY, C_TEXT);
        int sy = labelY + 8;
        int trackY = sy + 6;
        ctx.fill(x, trackY, x + INNER_W, trackY + 6, C_TRACK);
        int knobX = x + (int) Math.round(clamp(frac, 0.0, 1.0) * INNER_W);
        ctx.fill(x, trackY, knobX, trackY + 6, C_BORDER);
        ctx.fill(knobX - 3, sy + 3, knobX + 3, sy + 15, 0xFFFFFFFF);
    }

    private void drawCentered(DrawContext ctx, String text, int cx, int y, int color) {
        int w = this.textRenderer.getWidth(text);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(text), cx - w / 2, y, color);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
