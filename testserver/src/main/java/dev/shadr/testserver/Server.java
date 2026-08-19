/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.testserver;

import com.sun.net.httpserver.HttpServer;
import dev.shadr.core.PlayerId;
import dev.shadr.core.Rgb;
import dev.shadr.core.config.EditorWebConfig;
import dev.shadr.core.cursor.CursorPredictor;
import dev.shadr.core.editor.EditorLauncher;
import dev.shadr.core.editor.EditorServer;
import dev.shadr.core.editor.FileDocumentSource;
import dev.shadr.core.page.EffectDef;
import dev.shadr.core.page.Page;
import dev.shadr.core.page.PageLoader;
import dev.shadr.core.hud.PageRenderer;
import dev.shadr.core.session.UiSession;
import dev.shadr.core.shader.EnvironmentEffect;
import dev.shadr.core.shader.EnvironmentSettings;
import dev.shadr.core.shader.EnvironmentSource;
import dev.shadr.core.shader.ShaderApi;
import dev.shadr.core.shader.ShaderLoader;
import dev.shadr.core.spi.WorldAnchor;
import dev.shadr.minestom.MinestomBridge;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.command.builder.Command;
import net.minestom.server.timer.TaskSchedule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Server {
    private static final int MC_PORT = 25565;
    private static final int HTTP_PORT = 25566;
    private static final Path PACK_DIR = Path.of("out", "pack");
    private static final Path REPO_ROOT = Path.of(".");

    private static final long LINK_TTL_MILLIS = 30L * 60 * 1000;

    private static InstanceContainer instance;
    private static MinestomBridge bridge;

    private static Page demoPage;

    private static dev.shadr.minestom.MinestomAnvilCapture textCapture;
    private static PageRenderer renderer;
    private static Map<String, EffectDef> effects = Map.of();

    private static final Map<String, UiSession> sessions = new HashMap<>();

    private static EditorServer editorServer;
    private static int shaderHandleCounter;

    private static final ShaderLoader SHADERS =
            new ShaderLoader(REPO_ROOT.resolve("shaders/items").toFile());

    private static final EnvironmentSettings ENVIRONMENT =
            new EnvironmentSettings(REPO_ROOT.resolve("shaders/environment.properties").toFile());

    private static ShaderApi shaderApi;

    private static volatile byte[] packZip;
    private static volatile String packSha1;
    private static volatile String packUrl;

    private static final Object PACK_LOCK = new Object();

    private static volatile dev.shadr.pack.UiImageAtlas.BuildResult lastAtlas =
            new dev.shadr.pack.UiImageAtlas.BuildResult(java.util.Map.of(), java.util.List.of());

    public static void main(String[] args) throws Exception {
        loadPage();

        publishPack(zipDirContents(resolvePackDir()));
        startPackHost();
        System.out.println("[shadr] hosting pack (" + packZip.length + " bytes, sha1=" + packSha1
                + ") at " + packUrl);

        final MinecraftServer server = MinecraftServer.init();
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setChunkSupplier(LightingChunk::new);
        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        instance.setTime(6000);

        bridge = new MinestomBridge(
                name -> instance,
                () -> ENVIRONMENT.isEnabled(EnvironmentEffect.FROSTED_GLASS));
        bridge.install();
        shaderApi = new ShaderApi(bridge, SHADERS::load);

        textCapture = new dev.shadr.minestom.MinestomAnvilCapture((player, elementId, value) -> {
            final UiSession session = sessions.get(player.getUuid());
            if (session == null) return;
            if (session.setInputValue(elementId, value)) {
                bridge.hud().apply(player, session.draws());
            }
        });
        textCapture.onLog(message -> System.out.println("[shadr] " + message));
        textCapture.install(MinecraftServer.getGlobalEventHandler());

        bridge.input().onSample(sample -> {
            final UiSession session = sessions.get(sample.getPlayer().getUuid());
            if (session == null) return kotlin.Unit.INSTANCE;
            final var mapper = bridge.inputSource().mapperFor(sample.getPlayer());
            final boolean changed = session.update(
                    sample.getCursor(),
                    mapper == null ? 0.0 : mapper.getDeltaX(),
                    mapper == null ? 0.0 : mapper.getDeltaY(),
                    sample.getPingMillis());
            boolean clicked = false;
            if (sample.getLeftClick() || sample.getRightClick()) {
                session.click(sample.getRightClick());
                openFocusedInput(sample.getPlayer(), session);
                clicked = true;
            }
            if (changed || clicked) bridge.hud().apply(sample.getPlayer(), session.draws());
            return kotlin.Unit.INSTANCE;
        });

        final GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 42, 0));
        });
        events.addListener(PlayerSpawnEvent.class, event -> {
            final Player player = event.getPlayer();
            player.setGameMode(GameMode.CREATIVE);
            sendPack(new PlayerId(player.getUuid().toString()));
            player.sendMessage(Component.text(
                    "shadr: /ui to open the demo page, /editor for a browser link."));
        });

        registerCommands();
        startEditor();

        if (Boolean.parseBoolean(System.getProperty("shadr.pack.rebuild", "true"))) {
            System.out.println("[shadr] rebuilding the pack so launch properties apply");
            rebuildPack();
        }

        MinecraftServer.setCompressionThreshold(Integer.getInteger("shadr.compression", 256));

        server.start("0.0.0.0", MC_PORT);
        final java.util.concurrent.atomic.AtomicLong audioTick =
                new java.util.concurrent.atomic.AtomicLong();
        MinecraftServer.getSchedulerManager()
                .buildTask(() -> tickAudio(audioTick.getAndIncrement()))
                .repeat(TaskSchedule.tick(1))
                .schedule();
        MinecraftServer.getSchedulerManager()
                .buildTask(Server::tickStreams)
                .repeat(TaskSchedule.tick(1))
                .schedule();

        System.out.println("[shadr] server up on :" + MC_PORT + ". Join with 26.2.");
    }

    private static final java.util.Map<String, java.util.Map<String, Long>> AUDIO_DUE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.List<dev.shadr.core.video.VideoAudio.Track> audioTracks() {
        return dev.shadr.core.video.VideoAudio.INSTANCE.tracksOf(demoPage, id -> {
            for (dev.shadr.pack.VideoAssets.Source source : VIDEO_CACHE) {
                if (source.getClip().getId().equals(id) && source.getAudio() != null) {
                    return source.getClip();
                }
            }
            return null;
        });
    }

    private static void tickAudio(long tick) {
        final java.util.List<dev.shadr.core.video.VideoAudio.Track> tracks = audioTracks();
        if (tracks.isEmpty()) return;

        for (String uuid : sessions.keySet()) {
            final PlayerId id = new PlayerId(uuid);
            final kotlin.Pair<java.util.List<dev.shadr.core.video.VideoAudio.Track>,
                    java.util.Map<String, Long>> step =
                    dev.shadr.core.video.VideoAudio.INSTANCE.step(
                            tracks, tick, AUDIO_DUE.getOrDefault(uuid, java.util.Map.of()));

            for (dev.shadr.core.video.VideoAudio.Track track : step.getFirst()) {
                AUDIO_HOST.playSound(id, track.getSound(), 1.0);
            }
            AUDIO_DUE.put(uuid, step.getSecond());
        }
    }

    private static void stopAudio(PlayerId id) {
        AUDIO_DUE.remove(id.getUuid());
        for (dev.shadr.core.video.VideoAudio.Track track : audioTracks()) {
            AUDIO_HOST.stopSound(id, track.getSound());
        }
    }

    private static final LoggingActionHost AUDIO_HOST = new LoggingActionHost();

    private static void openUi(Player player) {
        final PlayerId id = new PlayerId(player.getUuid().toString());
        bridge.inputSource().useScreen(
                demoPage.getScreen().getWidth(),
                demoPage.getScreen().getHeight(),
                demoPage.getScreen().getCursorSpeed());

        final Runnable mount = () -> {
            bridge.inputSource().resetMapper(id);
            bridge.hud().mount(id);

            final UiSession session = new UiSession(
                    id,
                    demoPage,
                    renderer,
                    effects,
                    new dev.shadr.core.action.ActionRunner(new LoggingActionHost()),
                    new CursorPredictor(),
                    placeholdersFor(id));
            sessions.put(id.getUuid(), session);
            bridge.hud().apply(id, session.draws());
            startStreamIfNeeded(id);
            System.out.println("[shadr] UI open for " + player.getUsername());
        };

        if (demoPage.getScreen().getLocksCamera()) {
            bridge.cameraControl().start(id, mount::run);
        } else {
            mount.run();
        }
    }

    static void switchPage(PlayerId player, String name) {
        final Page next = pages.get(name);
        if (next == null) {
            System.out.println("[shadr] no page named '" + name + "'; have " + pages.keySet());
            return;
        }
        final UiSession session = sessions.get(player.getUuid());
        if (session == null) return;

        bridge.inputSource().useScreen(
                next.getScreen().getWidth(),
                next.getScreen().getHeight(),
                next.getScreen().getCursorSpeed());
        bridge.inputSource().resetMapper(player);

        session.openPage(next);
        System.out.println("[shadr] switched to page '" + name + "' (hud="
                + next.getScreen().getHud() + ")");
        applyCameraFor(player, next, () -> bridge.hud().apply(player, session.draws()));
    }

    private static dev.shadr.core.page.PlaceholderResolver placeholdersFor(PlayerId id) {
        return dev.shadr.core.page.PlaceholderResolver.Companion.chain(
                new dev.shadr.core.page.InputPlaceholders((player, field) -> {
                    final UiSession session = sessions.get(player.getUuid());
                    if (session == null) return null;
                    for (Map.Entry<String, String> entry : session.inputs().entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(field)) return entry.getValue();
                    }
                    return null;
                }),
                new dev.shadr.core.page.BuiltinPlaceholders(() -> {
                    final Player target = MinecraftServer.getConnectionManager()
                            .getOnlinePlayerByUuid(UUID.fromString(id.getUuid()));
                    final long time = target == null ? 0L : target.getInstance().getTime() % 24000L;
                    final int hours = (int) ((time / 1000L + 6L) % 24L);
                    final int minutes = (int) ((time % 1000L) * 60L / 1000L);
                    return new dev.shadr.core.page.BuiltinPlaceholders.Snapshot(
                            target == null ? "" : target.getUsername(),
                            MinecraftServer.getConnectionManager().getOnlinePlayerCount(),
                            MinecraftServer.getConnectionManager().getOnlinePlayerCount(),
                            "20.0",
                            target == null ? 0 : target.getLatency(),
                            "demo",
                            String.format("%02d:%02d", hours, minutes));
                }));
    }

    private static void openFocusedInput(PlayerId id, UiSession session) {
        if (textCapture == null) return;
        final Player player = MinecraftServer.getConnectionManager()
                .getOnlinePlayerByUuid(UUID.fromString(id.getUuid()));
        if (player == null) return;

        final String focused = session.getFocusedInput();
        if (focused == null) {
            textCapture.release(player);
            return;
        }
        if (focused.equals(textCapture.focusedElement(player))) return;

        for (dev.shadr.core.page.Element element : session.getCurrentPage().getElements()) {
            if (!element.getId().equals(focused) || element.getInput() == null) continue;
            final String current = session.inputValue(focused) != null
                    ? session.inputValue(focused)
                    : element.getInput().getValue();
            textCapture.focus(player, focused, current, element.getInput().getMaxLength());
            return;
        }
    }

    private static void applyCameraFor(PlayerId player, Page page, Runnable whenReady) {
        final boolean wantsSeat = page.getScreen().getLocksCamera();
        if (wantsSeat == bridge.cameraControl().isSeated(player)) {
            whenReady.run();
            return;
        }

        bridge.hud().clear(player);
        bridge.forget(player);
        bridge.cameraControl().setClickTargetsEnabled(player, false);
        bridge.cameraControl().stop(player);

        final Runnable mount = () -> {
            bridge.inputSource().resetMapper(player);
            bridge.hud().mount(player);
            whenReady.run();
        };
        if (wantsSeat) {
            bridge.cameraControl().start(player, mount::run);
        } else {
            bridge.cameraControl().startFollowing(player, mount::run);
        }
    }

    private static void closeUi(Player player) {
        final PlayerId id = new PlayerId(player.getUuid().toString());
        if (textCapture != null) textCapture.release(player);
        bridge.streamSink().stop(id);
        sessions.remove(id.getUuid());
        stopAudio(id);
        bridge.forget(id);
    }

    private static final dev.shadr.core.stream.StreamGeometry STREAM =
            dev.shadr.core.stream.StreamPresets.INSTANCE.carrier();

    private static void tickStreams() {
        for (String uuid : sessions.keySet()) {
            final PlayerId id = new PlayerId(uuid);
            if (bridge.streamSink().isActive(id)) bridge.streamSink().tick(id, STREAM);
        }
    }

    private static java.io.File streamedSourceFor(String clipId) {
        final java.io.File dir = REPO_ROOT.resolve("contents/videos").toFile();
        final java.io.File[] entries = dir.listFiles();
        if (entries == null) return null;
        for (java.io.File entry : entries) {
            if (entry.isFile() && entry.getName().toLowerCase(java.util.Locale.ROOT)
                    .startsWith(clipId + ".")) {
                return entry;
            }
        }
        return null;
    }

    private static void startStreamIfNeeded(PlayerId id) {
        if (demoPage == null) return;
        for (dev.shadr.core.page.Element element : demoPage.getElements()) {
            if (element.getType() != dev.shadr.core.page.ElementType.VIDEO) continue;
            if (!element.getStream() || !element.getEnabled()) continue;
            final String clip = element.getItem();
            if (clip == null) continue;
            final java.io.File source = streamedSourceFor(clip);
            if (source == null) {
                System.out.println("[shadr] streamed clip '" + clip + "' has no source file");
                continue;
            }
            final var sink = bridge.streamSink();
            final String startFailure = sink.start(id, STREAM);
            if (startFailure != null) {
                System.out.println("[shadr] stream: " + startFailure);
                return;
            }
            final String playFailure = sink.playVideo(id, source);
            if (playFailure != null) System.out.println("[shadr] stream: " + playFailure);
            return;
        }
    }

    private static void registerCommands() {
        final var manager = MinecraftServer.getCommandManager();

        final var stream = new Command("stream");
        stream.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("players only."));
                return;
            }
            final PlayerId id = new PlayerId(player.getUuid().toString());
            final var sink = bridge.streamSink();
            final String[] parts = ctx.getInput().trim().split("\\s+");
            final String action = parts.length > 1 ? parts[1].toLowerCase(java.util.Locale.ROOT) : "status";

            switch (action) {
                case "stop" -> {
                    sink.stop(id);
                    player.sendMessage(Component.text("shadr: stream stopped."));
                }
                case "video" -> {
                    if (parts.length < 3) {
                        player.sendMessage(Component.text("shadr: usage /stream video <path under the repo root>"));
                        return;
                    }
                    if (!sink.isActive(id)) {
                        final String failure = sink.start(id, STREAM);
                        if (failure != null) {
                            player.sendMessage(Component.text("shadr: " + failure));
                            return;
                        }
                    }
                    final String failure = sink.playVideo(id, REPO_ROOT.resolve(parts[2]).toFile());
                    player.sendMessage(Component.text(
                            failure != null ? "shadr: " + failure : "shadr: playing " + parts[2] + "."));
                }
                default -> player.sendMessage(Component.text(
                        sink.isActive(id)
                                ? "shadr: stream active, " + STREAM.getSlots() + " slot(s), "
                                        + sink.bytesSent(id) + " byte(s), "
                                        + sink.framesShown(id) + " frame(s)"
                                        + (sink.codecStatus(id) != null ? ", " + sink.codecStatus(id) : "")
                                        + (sink.videoFailure(id) != null
                                                ? ", ffmpeg: " + sink.videoFailure(id) : "")
                                : "shadr: stream inactive"));
            }
        });
        manager.register(stream);

        final var ui = new Command("ui");
        ui.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player player)) return;
            if (sessions.containsKey(player.getUuid().toString())) {
                closeUi(player);
                player.sendMessage(Component.text("shadr: UI closed."));
            } else {
                openUi(player);
                player.sendMessage(Component.text("shadr: UI open."));
            }
        });
        manager.register(ui);

        final var editor = new Command("editor");
        editor.setDefaultExecutor((sender, ctx) -> {
            if (editorServer == null) {
                sender.sendMessage(Component.text("shadr: editor is not running."));
                return;
            }
            final String label = sender instanceof Player p ? p.getUuid().toString() : "console";
            editorServer.revokeIssued(label);
            final String url = editorServer.mintUrl(label, LINK_TTL_MILLIS, null);
            final String link = url != null ? url : editorServer.url();
            sender.sendMessage(Component.text("shadr: click meh!")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(link))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(link))));
        });
        manager.register(editor);

        final var shader = new Command("shader");
        shader.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("players only."));
                return;
            }
            final String[] parts = ctx.getInput().trim().split("\\s+");

            if (parts.length < 2 || parts[1].equalsIgnoreCase("list")) {
                sender.sendMessage(Component.text("shadr shaders: " + shaderApi.shaders().stream()
                        .map(dev.shadr.core.shader.ShaderDef::getId)
                        .reduce((a, b) -> a + ", " + b).orElse("none")
                        + "  |  placed: " + String.join(", ",
                            shaderApi.placed().isEmpty() ? List.of("none") : shaderApi.placed())));
                return;
            }
            if (parts[1].equalsIgnoreCase("clear")) {
                sender.sendMessage(Component.text(
                        "shadr: removed " + shaderApi.despawnAll() + " shader display(s)."));
                return;
            }

            final String id = parts[1].toLowerCase();
            final double scale = parts.length > 2 ? parseDouble(parts[2], 2.0) : 2.0;
            final Pos at = player.getPosition()
                    .add(player.getPosition().direction().mul(Math.max(2.0, scale * 1.5)))
                    .withY(player.getPosition().y() + player.getEyeHeight());

            final String handle = player.getUsername() + "_" + id + "_" + (++shaderHandleCounter);
            final String failure = shaderApi.spawn(
                    handle, id, new WorldAnchor("world", at.x(), at.y(), at.z()),
                    scale, Rgb.Companion.getWHITE(),
                    dev.shadr.core.spi.BillboardMode.CENTER, 1000f);

            sender.sendMessage(Component.text(failure != null
                    ? "shadr: " + failure
                    : "shadr: placed '" + id + "' as '" + handle + "'; /shader clear to remove."));
        });
        manager.register(shader);
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static void startEditor() {
        final boolean insecure = Boolean.getBoolean("shadr.editor.insecure");
        editorServer = EditorLauncher.INSTANCE.start(
                new EditorWebConfig(
                        true,
                        EditorServer.DEFAULT_PORT,
                        "127.0.0.1",
                        "",
                        insecure,
                        "",
                        REPO_ROOT.resolve("editor/build/web").toAbsolutePath().toString(),
                        "", "", ""),
                Path.of("out").toFile(),
                new FileDocumentSource(
                        REPO_ROOT.resolve("protocol/pages").toFile(),
                        REPO_ROOT.resolve("protocol/components").toFile(),
                        REPO_ROOT.resolve("protocol/effects").toFile()),
                edited -> {
                    pages.put(edited.getName(), edited);
                    if (edited.getName().equals(PAGE_NAME)) demoPage = edited;
                    for (Map.Entry<String, UiSession> open : sessions.entrySet()) {
                        if (!open.getValue().getCurrentPage().getName().equals(edited.getName())) continue;
                        open.getValue().refreshPage(edited);
                        bridge.hud().apply(new PlayerId(open.getKey()), open.getValue().draws());
                    }
                    return kotlin.Unit.INSTANCE;
                },
                SHADERS,
                ENVIRONMENT,
                new EnvironmentSource(REPO_ROOT.resolve("shaders").toFile()),
                Server::rebuildPack,
                new dev.shadr.pack.AtlasImageSource(
                        REPO_ROOT.resolve("assets/shadr/contents").toFile(),
                        () -> lastAtlas),
                new BakeOnUpload(
                        new dev.shadr.pack.LibraryVideoSource(REPO_ROOT.resolve("contents").toFile())),
                line -> {
                    System.out.println("[shadr] " + line);
                    return kotlin.Unit.INSTANCE;
                });
    }

    static final String SELECTOR_PAGE = "__pages";

    private static final String PAGE_NAME =
            System.getenv().getOrDefault("SHADR_PAGE", SELECTOR_PAGE);

    private static final Map<String, Page> pages = new java.util.LinkedHashMap<>();

    private static void loadPage() {
        final PageLoader loader = new PageLoader(
                REPO_ROOT.resolve("protocol/pages").toFile(),
                REPO_ROOT.resolve("protocol/components").toFile(),
                REPO_ROOT.resolve("protocol/effects").toFile());
        effects = loader.loadEffects();

        pages.clear();
        final Map<String, Page> loaded = new java.util.TreeMap<>(loader.loadPages(loader.loadComponents()));
        pages.putAll(loaded);
        loader.getIssues().forEach(issue -> System.out.println("[shadr] page issue: " + issue));
        if (pages.isEmpty()) throw new IllegalStateException("protocol/pages has no loadable page");

        pages.put(SELECTOR_PAGE, selectorPage(loaded.keySet(), screenTemplate(loaded)));

        demoPage = pages.get(PAGE_NAME);
        if (demoPage == null) {
            throw new IllegalStateException("no page named '" + PAGE_NAME + "'; have " + pages.keySet());
        }
        renderer = new PageRenderer();
        System.out.println("[shadr] " + loaded.size() + " page(s) loaded: " + loaded.keySet());
        System.out.println("[shadr] page '" + PAGE_NAME + "': "
                + demoPage.getElements().size() + " element(s)");
    }

    private static dev.shadr.core.page.ScreenDef screenTemplate(java.util.Map<String, Page> loaded) {
        for (Page page : loaded.values()) return page.getScreen();
        return new dev.shadr.core.page.ScreenDef();
    }

    private static Page selectorPage(
            java.util.Collection<String> names, dev.shadr.core.page.ScreenDef screen) {
        final List<Object> blocks = new java.util.ArrayList<>();

        blocks.add(map(
                "type", "block",
                "id", "selector_dim",
                "layer", 0.0,
                "color", "08080b",
                "opacity", 220,
                "position", map("x", 0, "y", 0),
                "size", map("width", (int) screen.getWidth(), "height", (int) screen.getHeight())));

        blocks.add(map(
                "type", "text",
                "id", "selector_title",
                "layer", 20.0,
                "color", "e8e8f0",
                "text", "shadr pages",
                "font", "shadr_semibold",
                "position", map("x", "halfWidth", "y", 140),
                "size", map("width", 44, "height", 44),
                "textAlign", "center"));

        final int columns = names.size() > ROWS_PER_COLUMN ? 2 : 1;
        final int rows = (names.size() + columns - 1) / columns;
        int index = 0;
        for (String name : names) {
            final int column = index / rows;
            final int row = index % rows;
            final double offset = (column - (columns - 1) / 2.0) * (BUTTON_W + 30) - BUTTON_W / 2.0;
            final String x = offset < 0
                    ? "halfWidth - " + (-offset)
                    : "halfWidth + " + offset;
            final int y = 230 + row * (BUTTON_H + 14);

            blocks.add(map(
                    "type", "block_rounded",
                    "id", "page_" + name,
                    "layer", 10.0,
                    "color", "1b1b24",
                    "position", map("x", x, "y", y),
                    "size", map("width", BUTTON_W, "height", BUTTON_H),
                    "rounding", map("size", "small"),
                    "outline", map("size", 1, "color", "2a2a36"),
                    "hoverEffect", "lift",
                    "clickEffect", "press",
                    "onClickAction", List.of("sound: shadr.click", "redirect: " + name)));

            blocks.add(map(
                    "type", "text",
                    "id", "page_" + name + "_label",
                    "layer", 20.0,
                    "color", "e8e8f0",
                    "text", name,
                    "position", map("x", labelX(offset), "y", y + BUTTON_H / 2.0 - 4),
                    "size", map("width", 24, "height", 24),
                    "textAlign", "center",
                    "disableHitbox", true));
            index++;
        }

        final dev.shadr.core.page.TemplateResolver resolver = new dev.shadr.core.page.TemplateResolver();
        final List<dev.shadr.core.page.Element> elements = resolver.resolve(blocks, screen);
        resolver.getIssues().forEach(issue -> System.out.println("[shadr] selector issue: " + issue));
        return new Page(SELECTOR_PAGE, screen, elements, List.of());
    }

    private static String labelX(double offset) {
        final double centre = offset + BUTTON_W / 2.0;
        return centre < 0 ? "halfWidth - " + (-centre) : "halfWidth + " + centre;
    }

    private static Map<String, Object> map(Object... pairs) {
        final Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    private static final int BUTTON_W = 280;

    private static final int BUTTON_H = 44;

    private static final int ROWS_PER_COLUMN = 9;

    private static final class LoggingActionHost implements dev.shadr.core.action.ActionHost {
        @Override public void runAsPlayer(PlayerId player, String command) { log("command", command); }
        @Override public void runAsConsole(String command) { log("console", command); }
        @Override public void message(PlayerId player, String text) { log("message", text); }
        @Override public void playSound(PlayerId player, String sound, double volume) {
            final Player target = MinecraftServer.getConnectionManager()
                    .getOnlinePlayerByUuid(UUID.fromString(player.getUuid()));
            if (target == null) return;
            target.playSound(net.kyori.adventure.sound.Sound.sound()
                    .type(net.kyori.adventure.key.Key.key(sound.toLowerCase()))
                    .volume((float) volume)
                    .pitch(1f)
                    .build());
        }
        @Override public void stopSound(PlayerId player, String sound) {
            final Player target = MinecraftServer.getConnectionManager()
                    .getOnlinePlayerByUuid(UUID.fromString(player.getUuid()));
            if (target == null) return;
            target.stopSound(net.kyori.adventure.sound.SoundStop.named(
                    net.kyori.adventure.key.Key.key(sound.toLowerCase())));
        }
        @Override public void closePage(PlayerId player) {
            final Player target = MinecraftServer.getConnectionManager()
                    .getOnlinePlayerByUuid(UUID.fromString(player.getUuid()));
            if (target != null) closeUi(target);
        }
        @Override public void openPage(PlayerId player, String page, boolean replacing) {
            log("open", page);
            switchPage(player, page);
        }
        @Override public void teleport(PlayerId player, String destination) { log("teleport", destination); }
        @Override public boolean hasPermission(PlayerId player, String permission) { return true; }
        @Override public void scheduleTicks(long ticks, kotlin.jvm.functions.Function0<kotlin.Unit> task) {
            MinecraftServer.getSchedulerManager()
                    .buildTask(task::invoke)
                    .delay(TaskSchedule.tick((int) ticks))
                    .schedule();
        }
        @Override public String resolvePlaceholders(PlayerId player, String text) {
            return dev.shadr.core.page.PlaceholderScanner.INSTANCE.apply(
                    text, player, placeholdersFor(player));
        }

        private static void log(String verb, String argument) {
            System.out.println("[shadr] action " + verb + (argument.isEmpty() ? "" : ": " + argument));
        }
    }

    private static Path resolvePackDir() {
        if (Files.isDirectory(PACK_DIR)) return PACK_DIR;
        throw new IllegalStateException(
                "no pack at " + PACK_DIR.toAbsolutePath() + ", run:\n"
                        + "  ./gradlew :resourcepack:run --args=\"$PWD/shaders $PWD/out/pack "
                        + "$PWD/assets/font $PWD/assets/shadr/sounds\"");
    }

    private static byte[] zipDirContents(Path dir) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer); Stream<Path> walk = Files.walk(dir)) {
            final List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                final ZipEntry entry = new ZipEntry(dir.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static String sha1Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static byte[] hexToBytes(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }

    private static void startPackHost() throws IOException {
        final HttpServer http = HttpServer.create(new InetSocketAddress("0.0.0.0", HTTP_PORT), 0);
        http.createContext("/", exchange -> {
            final byte[] payload = packZip;
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        http.setExecutor(null);
        http.start();
    }

    private static void publishPack(byte[] zipped) throws Exception {
        packZip = zipped;
        packSha1 = sha1Hex(zipped);
        packUrl = "http://127.0.0.1:" + HTTP_PORT + "/pack-" + packSha1 + ".zip";
    }

    private static void sendPack(PlayerId player) {
        bridge.pack().send(player, packUrl, hexToBytes(packSha1), true);
    }

    private static java.util.List<dev.shadr.pack.VideoAssets.Source> VIDEO_CACHE =
            java.util.List.of();

    private static String videoCacheKey = null;

    private static java.util.Set<String> streamedClipIds() {
        final java.util.Set<String> ids = new java.util.HashSet<>();
        if (demoPage != null) {
            for (dev.shadr.core.page.Element element : demoPage.getElements()) {
                if (element.getType() == dev.shadr.core.page.ElementType.VIDEO
                        && element.getStream() && element.getItem() != null) {
                    ids.add(element.getItem().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return ids;
    }

    private static final VideoBakeCache VIDEO_BAKE_CACHE =
            new VideoBakeCache(Path.of("out", "video-cache").toFile());

    private static final java.util.Set<String> pendingBakes =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    static void requestBake(String id) {
        final String key = id.toLowerCase(java.util.Locale.ROOT);
        VIDEO_BAKE_CACHE.forget(key);
        pendingBakes.add(key);
    }

    static void forgetBake(String id) {
        VIDEO_BAKE_CACHE.forget(id.toLowerCase(java.util.Locale.ROOT));
    }

    private static java.util.List<dev.shadr.pack.VideoAssets.Source> videoSources() {
        final java.io.File contents = REPO_ROOT.resolve("contents").toFile();
        final java.io.File dir = new java.io.File(contents, dev.shadr.pack.VideoLibrary.FOLDER);

        final java.io.File[] entries = dir.listFiles();
        if (entries == null) return List.of();
        java.util.Arrays.sort(entries, java.util.Comparator.comparing(java.io.File::getName));

        final List<dev.shadr.pack.VideoAssets.Source> sources = new java.util.ArrayList<>();
        final java.util.Map<String, java.io.File> byId = new java.util.LinkedHashMap<>();
        final List<String> unbaked = new java.util.ArrayList<>();

        for (java.io.File entry : entries) {
            final String id = nameWithoutExtension(entry.getName()).toLowerCase(java.util.Locale.ROOT);
            byId.put(id, entry);
            if (pendingBakes.contains(id)) continue;
            final dev.shadr.pack.VideoAssets.Source cached = VIDEO_BAKE_CACHE.load(id, entry);
            if (cached != null) {
                sources.add(cached);
            } else {
                unbaked.add(id);
            }
        }

        final java.util.Set<String> wanted;
        synchronized (pendingBakes) {
            wanted = new java.util.LinkedHashSet<>(pendingBakes);
            pendingBakes.clear();
        }
        wanted.retainAll(byId.keySet());

        if (!wanted.isEmpty()) {
            System.out.println("[shadr] baking " + wanted);
            final dev.shadr.pack.VideoImport importer = new dev.shadr.pack.VideoImport(
                    "ffmpeg", "ffprobe", line -> {
                        System.out.println("[shadr] " + line);
                        return kotlin.Unit.INSTANCE;
                    });
            final dev.shadr.pack.VideoLibrary.Result result =
                    new dev.shadr.pack.VideoLibrary(
                            contents, importer, 30.0, 3.0, 24,
                            dev.shadr.core.video.VideoBudget.MAX_HEIGHT,
                            streamedClipIds(), wanted).load();
            for (String issue : result.getIssues()) {
                System.out.println("[shadr] video: " + issue);
            }
            for (dev.shadr.pack.VideoAssets.Source baked : result.getSources()) {
                final String id = baked.getClip().getId().toLowerCase(java.util.Locale.ROOT);
                final java.io.File source = byId.get(id);
                if (source != null) VIDEO_BAKE_CACHE.store(id, source, baked);
                sources.add(baked);
            }
        }

        if (!unbaked.isEmpty()) {
            System.out.println("[shadr] not baked (upload through the editor to encode): " + unbaked);
        }
        return sources;
    }

    private static String nameWithoutExtension(String name) {
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static boolean rebuildPack() {
        synchronized (PACK_LOCK) {
            try {
                new dev.shadr.pack.PackGenerator(
                        REPO_ROOT.resolve("shaders").toFile(),
                        REPO_ROOT.resolve("assets/font").toFile(),
                        REPO_ROOT.resolve("assets/shadr/sounds").toFile(),
                        false,
                        SHADERS.load(),
                        ENVIRONMENT.all(),
                        videoSources(),
                        STREAM,
                        true)
                        .build(PACK_DIR.toFile(), true);
                lastAtlas = new dev.shadr.pack.UiImageAtlas(
                        REPO_ROOT.resolve("assets/shadr/contents").toFile(),
                        PACK_DIR.toFile(),
                        Path.of("out", "uiimages_codepoints.properties").toFile())
                        .rebuild();
                publishPack(zipDirContents(PACK_DIR));
            } catch (Exception failure) {
                System.out.println("[shadr] pack rebuild failed: " + failure);
                return false;
            }
        }
        System.out.println("[shadr] pack rebuilt (" + packZip.length + " bytes, sha1=" + packSha1 + ")");
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                sendPack(new PlayerId(player.getUuid().toString()));
            }
        });
        return true;
    }
}
