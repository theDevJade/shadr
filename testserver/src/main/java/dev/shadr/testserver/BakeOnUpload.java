/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.testserver;

import dev.shadr.core.editor.VideoEntry;
import dev.shadr.core.editor.VideoSource;
import java.util.List;
import org.jetbrains.annotations.NotNull;

final class BakeOnUpload implements VideoSource {

    private final VideoSource delegate;

    BakeOnUpload(VideoSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public String write(@NotNull String name, @NotNull String extension, byte @NotNull [] bytes) {
        final String failure = delegate.write(name, extension, bytes);
        if (failure == null) Server.requestBake(name);
        return failure;
    }

    @Override
    public boolean delete(@NotNull String name) {
        final boolean removed = delegate.delete(name);
        if (removed) Server.forgetBake(name);
        return removed;
    }

    @NotNull
    @Override
    public List<VideoEntry> list() {
        return delegate.list();
    }
}
