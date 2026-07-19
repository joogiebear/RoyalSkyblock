package com.mystipixel.royalskyblock.gui.menu;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A fixed, hand-placed slot from a menu's {@code slots:} list — its resolved 0-based index, the item
 * to render, its lore, and the left/right-click effect lists. {@code id} is an optional semantic tag.
 *
 * <p>{@code content} pins a dynamic item to this exact slot: a slot with {@code content: size} is
 * rendered by code with that upgrade's icon (instead of the static {@code item}), letting admins
 * place dynamic entries at any row/column rather than auto-filling the mask.
 */
public record MenuSlot(int index,
                       String id,
                       @Nullable String content,
                       ItemSpec item,
                       List<String> lore,
                       List<MenuEffect> leftClick,
                       List<MenuEffect> rightClick,
                       boolean silent) {

    /**
     * {@code silent: true} suppresses the menu's click sound for this slot alone.
     *
     * <p>For a button that opens another menu, the destination announces itself when it opens — so
     * the click sound and the open sound land together and are heard as one doubled click. The menu
     * sound is menu-wide, so this is the only way to exempt a single button without taking the
     * feedback away from every other one.
     */
    public MenuSlot {
    }
}
