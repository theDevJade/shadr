/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;

public final class PacketBackends {

    private static final Map<String, String> MODULES = new LinkedHashMap<>();

    static {
        MODULES.put("1.21.6", "v1_21_8");
        MODULES.put("1.21.7", "v1_21_8");
        MODULES.put("1.21.8", "v1_21_8");
        MODULES.put("1.21.9", "v1_21_11");
        MODULES.put("1.21.10", "v1_21_11");
        MODULES.put("1.21.11", "v1_21_11");
        MODULES.put("26.0", "v26_1");
        MODULES.put("26.1", "v26_1");
        MODULES.put("26.1.1", "v26_1");
        MODULES.put("26.2", "v26_2");
    }

    private static final Map<String, String> FAMILIES = new LinkedHashMap<>();

    static {
        FAMILIES.put("26.0.", "v26_1");
        FAMILIES.put("26.1.", "v26_1");
        FAMILIES.put("26.2.", "v26_2");
    }

    public static final String SUPPORTED_RANGE = "1.21.6 through 26.2";

    private static final String ONE_21_PREFIX = "1.21.";

    private static final int FIRST_SUPPORTED_1_21_PATCH = 6;

    private PacketBackends() {}

    public static String moduleFor(String minecraftVersion) {
        String v = minecraftVersion.trim();
        String exact = MODULES.get(v);
        if (exact != null) return exact;
        for (Map.Entry<String, String> family : FAMILIES.entrySet()) {
            if (v.startsWith(family.getKey())) return family.getValue();
        }
        return oneTwentyOne(v);
    }

    private static String oneTwentyOne(String v) {
        if (!v.startsWith(ONE_21_PREFIX)) return null;
        int end = ONE_21_PREFIX.length();
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        if (end == ONE_21_PREFIX.length()) return null;
        int patch;
        try {
            patch = Integer.parseInt(v.substring(ONE_21_PREFIX.length(), end));
        } catch (NumberFormatException malformed) {
            return null;
        }
        if (patch < FIRST_SUPPORTED_1_21_PATCH) return null;
        return patch <= 8 ? "v1_21_8" : "v1_21_11";
    }

    public static String serverVersion() {
        try {
            return (String) Bukkit.class.getMethod("getMinecraftVersion").invoke(null);
        } catch (ReflectiveOperationException ignored) {
            String bukkit = Bukkit.getBukkitVersion();
            int dash = bukkit.indexOf('-');
            return dash > 0 ? bukkit.substring(0, dash) : bukkit;
        }
    }

    public static PacketBackend load() {
        String version = serverVersion();
        String module = moduleFor(version);
        if (module == null) {
            throw new IllegalStateException(
                    "shadr supports Minecraft " + SUPPORTED_RANGE + ", but this server is " + version);
        }
        try {
            Class<?> type = Class.forName("dev.shadr.paper.nms." + module + ".NmsPacketBackend");
            return (PacketBackend) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException(
                    "shadr could not load its " + module + " packet backend on Minecraft " + version, failure);
        }
    }
}
