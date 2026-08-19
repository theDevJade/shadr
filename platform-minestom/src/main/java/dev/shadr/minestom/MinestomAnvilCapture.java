/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.minestom;

import dev.shadr.core.PlayerId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.inventory.type.AnvilInventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.client.play.ClientNameItemPacket;

public final class MinestomAnvilCapture {

    public static final int ANVIL_LIMIT = 50;

    public interface Typed {

        void onValue(PlayerId player, String elementId, String value);
    }

    private static final class Open {
        final String elementId;
        final int maxLength;
        String committed = "";
        String box = "";

        Open(String elementId, int maxLength) {
            this.elementId = elementId;
            this.maxLength = maxLength;
        }

        String value() {
            return committed + box;
        }
    }

    private final Map<UUID, Open> open = new ConcurrentHashMap<>();

    private final Typed typed;

    private Consumer<String> log = message -> { };

    public MinestomAnvilCapture(Typed typed) {
        this.typed = typed;
    }

    public void onLog(Consumer<String> log) {
        this.log = log;
    }

    public void install(GlobalEventHandler events) {
        events.addListener(PlayerPacketEvent.class, event -> {
            if (!(event.getPacket() instanceof ClientNameItemPacket rename)) return;
            final Open session = open.get(event.getPlayer().getUuid());
            if (session == null) return;
            event.setCancelled(true);
            accept(event.getPlayer(), session, rename.itemName());
        });

        events.addListener(InventoryCloseEvent.class, event -> {
            if (open.containsKey(event.getPlayer().getUuid())) release(event.getPlayer());
        });
    }

    public String focusedElement(Player player) {
        final Open session = open.get(player.getUuid());
        return session == null ? null : session.elementId;
    }

    public void focus(Player player, String elementId, String current, int maxLength) {
        final Open existing = open.get(player.getUuid());
        if (existing != null && existing.elementId.equals(elementId)) return;

        final Open session = new Open(elementId, Math.max(1, maxLength));
        session.committed = current == null ? "" : current;
        open.put(player.getUuid(), session);

        openAnvil(player);
        log.accept("anvil open for '" + elementId + "' maxLength=" + session.maxLength
                + " current='" + session.committed + "'");
    }

    public void release(Player player) {
        if (open.remove(player.getUuid()) == null) return;
        player.closeInventory();
    }

    public void forget(PlayerId player) {
        open.remove(UUID.fromString(player.getUuid()));
    }

    private void accept(Player player, Open session, String box) {
        final String raw = box == null ? "" : box;

        if (raw.length() >= ANVIL_LIMIT && session.committed.length() + raw.length() < session.maxLength) {
            session.committed = clamp(session.committed + raw, session.maxLength);
            session.box = "";
            openAnvil(player);
            log.accept("anvil refilled for '" + session.elementId + "' at " + session.committed.length()
                    + " of " + session.maxLength);
            publish(player, session);
            return;
        }

        session.box = raw;
        publish(player, session);
    }

    private void publish(Player player, Open session) {
        typed.onValue(
                new PlayerId(player.getUuid().toString()),
                session.elementId,
                clamp(session.value(), session.maxLength));
    }

    private static String clamp(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void openAnvil(Player player) {
        final AnvilInventory anvil = new AnvilInventory(Component.empty());
        anvil.addItemStack(ItemStack.of(Material.PAPER)
                .with(DataComponents.CUSTOM_NAME, Component.empty())
                .with(DataComponents.ITEM_MODEL, "minecraft:air"));
        player.openInventory(anvil);
    }
}
