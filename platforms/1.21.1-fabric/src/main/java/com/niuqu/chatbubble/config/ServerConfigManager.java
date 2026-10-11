package com.niuqu.chatbubble.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ServerConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private ServerConfigManager() {}

    public static ServerConfig load(Path path) {
        if (Files.exists(path)) {
            try (Reader r = new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
                ServerConfig loaded = GSON.fromJson(r, ServerConfig.class);
                if (loaded != null) return clampLimits(loaded);
            } catch (Exception e) {
                LogUtils.getLogger().warn("[e33chat] Failed to read server config {}, keeping a backup", path, e);
                backupBrokenFile(path);
            }
        }
        ServerConfig def = ServerConfig.defaults();
        save(path, def);
        return def;
    }

    /**
     * The integer limits are typed straight out of JSON with no schema, so the one
     * load boundary corrects them to the ranges Forge/Neo pin with
     * {@code defineInRange}: the shared {@link com.niuqu.chatbubble.group.GroupHistoryStore}
     * rejects any history limit below 1, and {@link com.niuqu.chatbubble.group.GroupRules.Limits}
     * rejects {@code group_max_count < 1} / {@code group_max_members < 2}. Values already
     * inside their range — and keys absent from the file, which stay {@code null} so the
     * caller's defaults apply — are preserved exactly.
     */
    private static ServerConfig clampLimits(ServerConfig config) {
        config.group_max_count = clamp(config.group_max_count, 1, 500);
        config.group_max_members = clamp(config.group_max_members, 2, 1000);
        config.group_history_retention_days = clamp(config.group_history_retention_days, 1, 3650);
        config.group_history_max_messages = clamp(config.group_history_max_messages, 1, 1000000);
        config.group_history_max_mib = clamp(config.group_history_max_mib, 1, 4096);
        config.group_invite_lifetime_days = clamp(config.group_invite_lifetime_days, 1, 3650);
        return config;
    }

    private static Integer clamp(Integer value, int min, int max) {
        return value == null ? null : Math.min(Math.max(value, min), max);
    }

    /** Keep a corrupt file on disk for recovery instead of letting the
     *  defaults overwrite the only copy (mirrors the client ConfigManager). */
    private static void backupBrokenFile(Path path) {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis());
            Files.move(path, backup);
            LogUtils.getLogger().warn("[e33chat] Kept broken server config as {}", backup);
        } catch (Exception ignored) {}
    }

    public static boolean save(Path path, ServerConfig config) {
        try {
            Files.createDirectories(path.getParent());
            // Write-then-move so a crash mid-write cannot truncate the file.
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
                GSON.toJson(config, w);
            }
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LogUtils.getLogger().warn("[e33chat] Failed to save server config {}", path, e);
            return false;
        }
    }
}
