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
        MODULES.put("1.21", "v1_21_1");
        MODULES.put("1.21.1", "v1_21_1");
        MODULES.put("1.21.2", "v1_21_4");
        MODULES.put("1.21.3", "v1_21_4");
        MODULES.put("1.21.4", "v1_21_4");
        MODULES.put("1.21.5", "v1_21_5");
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
        FAMILIES.put("1.21.", "v1_21_11");
        FAMILIES.put("26.0.", "v26_1");
        FAMILIES.put("26.1.", "v26_1");
        FAMILIES.put("26.2.", "v26_2");
    }

    private PacketBackends() {}

    public static String moduleFor(String minecraftVersion) {
        String v = minecraftVersion.trim();
        String exact = MODULES.get(v);
        if (exact != null) return exact;
        for (Map.Entry<String, String> family : FAMILIES.entrySet()) {
            if (v.startsWith(family.getKey())) return family.getValue();
        }
        return null;
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
            throw new IllegalStateException("shadr has no packet backend for Minecraft " + version);
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
