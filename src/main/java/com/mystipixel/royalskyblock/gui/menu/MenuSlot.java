package com.mystipixel.royalskyblock.gui.menu;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A fixed slot from a menu's {@code slots:} list: its 0-based index, item, lore, click effects and sound.
 *
 * <p>{@code content} pins a dynamic item to this slot: {@code content: size} renders that upgrade's
 * icon here instead of the static {@code item}.
 */
public record MenuSlot(int index,
                       String id,
                       @Nullable String content,
                       ItemSpec item,
                       List<String> lore,
                       List<MenuEffect> leftClick,
                       List<MenuEffect> rightClick,
                       @Nullable MenuTemplate.SoundSpec sound) {

    // sound is the slot's own sound: block, or null for a silent click. There is no menu-wide default:
    // a navigation button would double up with the destination menu's open sound.
    public MenuSlot {
    }
}
