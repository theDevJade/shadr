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

        System.out.println("[shadr] baking video sources...");
        final long bakedAt = System.nanoTime();
        videoSources();
        System.out.printf("[shadr] video ready in %.1fs%n", (System.nanoTime() - bakedAt) / 1e9);

        server.start("0.0.0.0", MC_PORT);
        final java.util.concurrent.atomic.AtomicLong audioTick =
                new java.util.concurrent.atomic.AtomicLong();
        MinecraftServer.getSchedulerManager()
                .buildTask(() -> tickAudio(audioTick.getAndIncrement()))
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

        bridge.cameraControl().start(id, () -> {
            bridge.inputSource().resetMapper(id);
            bridge.hud().mount(id);

            final UiSession session = new UiSession(
                    id,
                    demoPage,
                    renderer,
                    effects,
                    new dev.shadr.core.action.ActionRunner(new LoggingActionHost()),
                    new CursorPredictor());
            sessions.put(id.getUuid(), session);
            bridge.hud().apply(id, session.draws());
            System.out.println("[shadr] UI open for " + player.getUsername());
        });
    }

    private static void closeUi(Player player) {
        final PlayerId id = new PlayerId(player.getUuid().toString());
        sessions.remove(id.getUuid());
        stopAudio(id);
        bridge.forget(id);
    }

    private static void registerCommands() {
        final var manager = MinecraftServer.getCommandManager();

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
                    demoPage = edited;
                    for (Map.Entry<String, UiSession> open : sessions.entrySet()) {
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
                new dev.shadr.pack.LibraryVideoSource(REPO_ROOT.resolve("contents").toFile()),
                line -> {
                    System.out.println("[shadr] " + line);
                    return kotlin.Unit.INSTANCE;
                });
    }

    private static final String PAGE_NAME =
            System.getenv().getOrDefault("SHADR_PAGE", "demo");

    private static void loadPage() {
        final PageLoader loader = new PageLoader(
                REPO_ROOT.resolve("protocol/pages").toFile(),
                REPO_ROOT.resolve("protocol/components").toFile(),
                REPO_ROOT.resolve("protocol/effects").toFile());
        effects = loader.loadEffects();
        demoPage = loader.loadPage(
                REPO_ROOT.resolve("protocol/pages/" + PAGE_NAME + ".yml").toFile(),
                loader.loadComponents());
        loader.getIssues().forEach(issue -> System.out.println("[shadr] page issue: " + issue));
        if (demoPage == null) {
            throw new IllegalStateException(
                    "protocol/pages/" + PAGE_NAME + ".yml did not load");
        }
        renderer = new PageRenderer();
        System.out.println("[shadr] page '" + PAGE_NAME + "': "
                + demoPage.getElements().size() + " element(s)");
    }

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
        @Override public void openPage(PlayerId player, String page, boolean replacing) { log("open", page); }
        @Override public void teleport(PlayerId player, String destination) { log("teleport", destination); }
        @Override public boolean hasPermission(PlayerId player, String permission) { return true; }
        @Override public void scheduleTicks(long ticks, kotlin.jvm.functions.Function0<kotlin.Unit> task) {
            MinecraftServer.getSchedulerManager()
                    .buildTask(task::invoke)
                    .delay(TaskSchedule.tick((int) ticks))
                    .schedule();
        }
        @Override public String resolvePlaceholders(PlayerId player, String text) { return text; }

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

    private static java.util.List<dev.shadr.pack.VideoAssets.Source> videoSources() {
        final java.io.File contents = REPO_ROOT.resolve("contents").toFile();
        final java.io.File dir = new java.io.File(contents, dev.shadr.pack.VideoLibrary.FOLDER);

        final StringBuilder key = new StringBuilder();
        final java.io.File[] entries = dir.listFiles();
        if (entries != null) {
            java.util.Arrays.sort(entries, java.util.Comparator.comparing(java.io.File::getName));
            for (java.io.File file : entries) {
                key.append(file.getName()).append(':')
                        .append(file.length()).append(':')
                        .append(file.lastModified()).append(';');
            }
        }
        if (key.toString().equals(videoCacheKey)) {
            return VIDEO_CACHE;
        }

        final dev.shadr.pack.VideoImport importer = new dev.shadr.pack.VideoImport(
                "ffmpeg", "ffprobe", line -> {
                    System.out.println("[shadr] " + line);
                    return kotlin.Unit.INSTANCE;
                });
        final dev.shadr.pack.VideoLibrary.Result result =
                new dev.shadr.pack.VideoLibrary(contents, importer, 30.0, 3.0).load();
        for (String issue : result.getIssues()) {
            System.out.println("[shadr] video: " + issue);
        }
        videoCacheKey = key.toString();
        VIDEO_CACHE = result.getSources();
        return VIDEO_CACHE;
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
                        videoSources())
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
