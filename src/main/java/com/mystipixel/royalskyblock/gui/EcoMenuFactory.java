package com.mystipixel.royalskyblock.gui;

import com.mystipixel.royalskyblock.gui.menu.MenuSlot;
import com.mystipixel.royalskyblock.gui.menu.MenuTemplate;
import com.mystipixel.royalskyblock.hooks.EcoHook;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import com.willfp.eco.core.gui.slot.SlotBuilder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds an eco {@link Menu} from a {@link MenuTemplate}.
 *
 * <p>This is the seam of the port onto eco's Menu API. {@code MenuTemplate} keeps its job — parsing
 * {@code gui/*.yml} — and this class replaces the hand-rolled Bukkit inventory rendering, so the menu
 * config files do not change shape at all. Being real eco menus means slots go through eco's item
 * pipeline, so EcoItems lore and rarity render correctly inside them, which the old
 * {@code Bukkit.createInventory} path could not do.
 *
 * <p><b>Coordinates.</b> {@link MenuSlot#index()} is a 0-based inventory index while eco's
 * {@code setSlot} is 1-based row/column ({@code MenuUtils.rowColumnToSlot} computes
 * {@code (column - 1) + (row - 1) * 9}). {@link #row}/{@link #column} do the conversion; getting this
 * wrong silently displaces every button, so it is deliberately in one place.
 *
 * <p><b>Per-player items.</b> Slots are built with {@link Slot#builder(Function)} rather than a fixed
 * {@link ItemStack} because item names and lore carry {@code %token%} placeholders that resolve per
 * viewer. eco calls the function on render, which also means a slot updates when its placeholders
 * change without the menu being rebuilt.
 */
public final class EcoMenuFactory {

    /** What to run when a configured slot is clicked. Supplied by the caller so effect execution, sounds and menu bookkeeping stay in one place. */
    @FunctionalInterface
    public interface SlotClickHandler {
        void onClick(Player player, MenuSlot slot, boolean rightClick);
    }

    private final EcoHook eco;

    public EcoMenuFactory(EcoHook eco) {
        this.eco = eco;
    }

    /**
     * Convert a template into a live eco menu.
     *
     * @param template     the parsed {@code gui/*.yml}
     * @param placeholders per-viewer {@code %token%} values for item names and lore
     * @param onClick      invoked for a configured slot; dynamic content slots are left to the caller
     */
    public Menu build(MenuTemplate template,
                      Function<Player, Map<String, String>> placeholders,
                      SlotClickHandler onClick) {
        MenuBuilder builder = Menu.builder(template.size() / 9)
                .setTitle(Text.legacy(template.title()));

        applyFiller(template, builder);

        for (MenuSlot slot : template.slots()) {
            builder.setSlot(row(slot.index()), column(slot.index()), toSlot(slot, placeholders, onClick));
        }
        return builder.build();
    }

    /**
     * Place the mask filler on every slot the menu hasn't otherwise claimed.
     *
     * <p>Deliberately not eco's {@code FillerMask}: the template resolves the mask to a single filler
     * item plus the set of content slots at parse time and does not retain the raw pattern, so
     * rebuilding a {@code FillerMask} would mean re-deriving a pattern we already reduced. Painting
     * the slots directly produces the same result. Content slots stay empty — dynamic menus fill them.
     */
    private void applyFiller(MenuTemplate template, MenuBuilder builder) {
        ItemStack filler = template.maskFiller();
        if (filler == null) {
            return;
        }
        List<Integer> contentSlots = template.contentSlots();
        for (int index = 0; index < template.size(); index++) {
            if (contentSlots.contains(index) || template.slotAt(index) != null) {
                continue;
            }
            builder.setSlot(row(index), column(index), Slot.builder(filler.clone()).build());
        }
    }

    private Slot toSlot(MenuSlot slot,
                        Function<Player, Map<String, String>> placeholders,
                        SlotClickHandler onClick) {
        SlotBuilder builder = Slot.builder(
                (Player player) -> slot.item().build(eco, placeholders.apply(player), slot.lore()));

        // Right-click falls through to the left-click effects when a slot declares none of its own,
        // matching the old engine: a button with a single action responds to either click.
        builder.onLeftClick((event, clicked) -> onClick.onClick((Player) event.getWhoClicked(), slot, false));
        builder.onRightClick((event, clicked) -> onClick.onClick((Player) event.getWhoClicked(), slot,
                !slot.rightClick().isEmpty()));
        return builder.build();
    }

    /** 0-based inventory index -> eco's 1-based row. Package-private so the round-trip is tested. */
    static int row(int index) {
        return index / 9 + 1;
    }

    /** 0-based inventory index -> eco's 1-based column. Package-private so the round-trip is tested. */
    static int column(int index) {
        return index % 9 + 1;
    }
}
