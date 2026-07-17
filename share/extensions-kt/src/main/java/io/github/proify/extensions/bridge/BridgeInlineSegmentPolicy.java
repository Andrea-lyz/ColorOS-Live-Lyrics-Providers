/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.extensions.bridge;

/**
 * Keeps display separators when a player exposes whitespace as an independent timed word.
 *
 * <p>A standalone whitespace token must not receive its own enhanced-LRC time tag. Consumers
 * commonly discard empty timed segments, which would turn {@code "也 也 也"} into
 * {@code "也也也"} and break matching against the line-timed lyric. Instead, the separator is
 * attached to the preceding visible segment.</p>
 */
public final class BridgeInlineSegmentPolicy {
    private BridgeInlineSegmentPolicy() {
    }

    /**
     * Appends one separator when {@code segment} contains whitespace only.
     *
     * @return {@code true} when the segment was standalone whitespace and must not be emitted
     * with a time tag; otherwise {@code false}
     */
    public static boolean appendStandaloneWhitespace(StringBuilder builder, String segment) {
        if (builder == null || segment == null || segment.isEmpty() || !isWhitespaceOnly(segment)) {
            return false;
        }

        if (builder.length() == 0) {
            return true;
        }
        char last = builder.charAt(builder.length() - 1);
        if (last != ']' && last != '>' && !Character.isWhitespace(last)) {
            builder.append(' ');
        }
        return true;
    }

    private static boolean isWhitespaceOnly(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
