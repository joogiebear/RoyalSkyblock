package com.mystipixel.royalskyblock.message;

/**
 * Why something a player asked for didn't happen: a lang.yml key and its placeholder pairs
 * ({@code "player", name}). Services return one instead of a sentence; the caller renders it with
 * {@link MessageManager#raw(Reason)}.
 */
public record Reason(String key, String... placeholders) {
}
