package com.gabdab.weboptimizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("weboptimizer.json");

    public static WebConfig INSTANCE = new WebConfig();

    public static final double REACH_MIN = 2.0;
    public static final double REACH_MAX = 4.5;

    // павутинка
    public boolean enabled = false;
    public int smoothness = 100;          // 0..1000 (більше = плавніше)
    public boolean returnSword = true;
    public double placeReach = 3.4;       // дальність постановки (блоки)

    // Auto Drain
    public boolean autoDrain = false;
    public int drainSmoothness = 600;     // 0..1000 (більше = плавніше, 500-800 схоже на людину)
    public int drainReactMin = 4;         // мін. "час реакції" у тіках (4 тіки = 0.2 с)
    public int drainReactMax = 8;         // макс. "час реакції" у тіках

    // позиція HUD
    public int hudX = 20;
    public int hudY = 20;

    public static void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(FILE)) {
            WebConfig loaded = GSON.fromJson(reader, WebConfig.class);
            if (loaded != null) {
                INSTANCE = loaded;
                INSTANCE.sanitize();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sanitize() {
        smoothness = Math.max(0, Math.min(1000, smoothness));
        drainSmoothness = Math.max(0, Math.min(1000, drainSmoothness));
        placeReach = Math.max(REACH_MIN, Math.min(REACH_MAX, placeReach));
        drainReactMin = Math.max(0, Math.min(40, drainReactMin));
        drainReactMax = Math.max(drainReactMin, Math.min(40, drainReactMax));
    }
}
