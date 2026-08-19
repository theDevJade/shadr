/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.testserver;

import dev.shadr.core.video.MosaicClip;
import dev.shadr.core.video.VideoClip;
import dev.shadr.pack.VideoAssets;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

final class VideoBakeCache {

    private static final int MAGIC = 0x53564243;

    private static final int VERSION = 1;

    private final File dir;

    VideoBakeCache(File dir) {
        this.dir = dir;
    }

    private File entryFor(String id) {
        return new File(dir, id + ".bin");
    }

    VideoAssets.Source load(String id, File source) {
        final File entry = entryFor(id);
        if (!entry.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(entry.toPath())))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
            if (in.readLong() != source.length() || in.readLong() != source.lastModified()) return null;

            final boolean streamed = in.readBoolean();
            final VideoClip clip = new VideoClip(
                    in.readUTF(), in.readInt(), in.readInt(), in.readInt(), in.readDouble(), in.readInt());

            MosaicClip mosaic = null;
            if (in.readBoolean()) {
                final int width = in.readInt();
                final int height = in.readInt();
                final int frames = in.readInt();
                final double fps = in.readDouble();
                final int texels = in.readInt();
                final int codebookBase = in.readInt();
                final int[] data = new int[in.readInt()];
                for (int i = 0; i < data.length; i++) data[i] = in.readInt();
                mosaic = new MosaicClip(width, height, frames, fps, data, texels, codebookBase);
            }

            byte[] audio = null;
            if (in.readBoolean()) {
                audio = new byte[in.readInt()];
                in.readFully(audio);
            }
            return new VideoAssets.Source(clip, mosaic, audio, streamed);
        } catch (IOException | RuntimeException unreadable) {
            entry.delete();
            return null;
        }
    }

    void store(String id, File source, VideoAssets.Source baked) {
        try {
            dir.mkdirs();
            final File entry = entryFor(id);
            try (DataOutputStream out = new DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(entry.toPath())))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(source.length());
                out.writeLong(source.lastModified());
                out.writeBoolean(baked.getStreamed());

                final VideoClip clip = baked.getClip();
                out.writeUTF(clip.getId());
                out.writeInt(clip.getWidth());
                out.writeInt(clip.getHeight());
                out.writeInt(clip.getFrameCount());
                out.writeDouble(clip.getFps());
                out.writeInt(clip.getIndex());

                final MosaicClip mosaic = baked.getMosaic();
                out.writeBoolean(mosaic != null);
                if (mosaic != null) {
                    out.writeInt(mosaic.getWidth());
                    out.writeInt(mosaic.getHeight());
                    out.writeInt(mosaic.getFrameCount());
                    out.writeDouble(mosaic.getFps());
                    out.writeInt(mosaic.getTexelCount());
                    out.writeInt(mosaic.getCodebookBase());
                    out.writeInt(mosaic.getData().length);
                    for (int value : mosaic.getData()) out.writeInt(value);
                }

                final byte[] audio = baked.getAudio();
                out.writeBoolean(audio != null);
                if (audio != null) {
                    out.writeInt(audio.length);
                    out.write(audio);
                }
            }
        } catch (IOException failure) {
            System.out.println("[shadr] could not cache baked video '" + id + "': " + failure.getMessage());
        }
    }

    void forget(String id) {
        entryFor(id).delete();
    }
}
