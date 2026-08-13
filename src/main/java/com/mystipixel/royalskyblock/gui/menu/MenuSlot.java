package com.mystipixel.royalskyblock.gui.menu;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A fixed, hand-placed slot from a menu's {@code slots:} list — its resolved 0-based index, the item
 * to render, its lore, the left/right-click effect lists, and the sound clicking it makes.
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
                       @Nullable MenuTemplate.SoundSpec sound) {

    /**
     * {@code sound} is the slot's own {@code sound:} block, or null when it declares none — in which
     * case clicking it makes no sound at all. There is no menu-wide click sound and no built-in
     * default: a click is silent unless a button asks to be heard.
     *
     * <p>That is deliberate rather than austere. A button that opens another menu produces two sounds
     * a tick apart — its click, then the destination announcing itself — and the ear hears one doubled
     * click. Every scheme that plays a click automatically has to then find ways to take it back:
     * first a per-slot {@code silent} flag, then deriving it from the effect list, then debouncing.
     * None of them could cover a code-registered action, whose behaviour is an opaque callback. Making
     * sound purely opt-in removes the problem at the source — a navigation button simply declares no
     * sound and lets its destination do the talking.
     */
    public MenuSlot {
    }
}
