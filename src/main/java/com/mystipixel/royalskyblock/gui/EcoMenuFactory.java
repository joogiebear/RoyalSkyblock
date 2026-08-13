package com.mystipixel.royalskyblock.gui;

import com.mystipixel.royalskyblock.gui.menu.MenuSlot;
import com.mystipixel.royalskyblock.gui.menu.MenuTemplate;
import com.mystipixel.royalskyblock.hooks.EcoHook;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import com.willfp.eco.core.gui.slot.SlotBuilder;
import com.willfp.eco.core.gui.slot.functional.SlotHandler;
import com.willfp.eco.core.gui.slot.functional.SlotProvider;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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

    /**
     * What to run when a code-registered (data-driven) slot is clicked.
     *
     * <p>Separate from {@link SlotClickHandler} because the two behave differently: a configured slot
     * consults its own {@code silent} flag and effect list, while a dynamic slot always sounds and its
     * action must be deferred off the click event. Both decisions belong to the caller.
     */
    @FunctionalInterface
    public interface DynamicClickHandler {
        void onClick(Player player, BiConsumer<Player, Boolean> action, boolean rightClick);
    }

    /**
     * One render of a data-driven menu: what every slot holds, and what the code-registered slots do
     * when clicked.
     *
     * <p>The legacy engine builds these menus imperatively against an {@link org.bukkit.inventory.Inventory}
     * and a {@code MenuHolder}. Rather than rewriting thirteen such builders, the caller runs them
     * against a scratch inventory and hands the result over as one of these — so the existing content
     * logic is reused verbatim and only the rendering changes.
     *
     * @param items   slot index -> item, sized to the menu; nulls are empty slots
     * @param actions slot index -> what a click does, taking the viewer and whether it was a right-click
     */
    public record Rendered(ItemStack[] items, Map<Integer, BiConsumer<Player, Boolean>> actions) {
    }

    /** Per-player menu-state key holding the current render snapshot. */
    private static final String STATE_RENDER = "royalskyblock_render";

    private final EcoHook eco;

    public EcoMenuFactory(EcoHook eco) {
        this.eco = eco;
    }

    /**
     * Convert a template into a live eco menu.
     *
     * @param template     the parsed {@code gui/*.yml}
     * @param title        the menu title, already {@code %token%}-substituted. Passed in rather than
     *                     read off the template because eco fixes the title at build time while
     *                     placeholders resolve per viewer, so the caller — which knows the viewer —
     *                     owns that substitution and the two render paths cannot drift apart.
     * @param placeholders per-viewer {@code %token%} values for item names and lore
     * @param onClick      invoked for a configured slot; dynamic content slots are left to the caller
     */
    public Menu build(MenuTemplate template,
                      String title,
                      Function<Player, Map<String, String>> placeholders,
                      SlotClickHandler onClick) {
        MenuBuilder builder = Menu.builder(template.size() / 9)
                .setTitle(Text.legacy(title));

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

    /**
     * Build a data-driven menu, where slot contents are computed per viewer rather than read from
     * config.
     *
     * <p>{@code render} is invoked once per render pass via eco's {@code onRender} and its result is
     * stashed in per-player menu state; every slot then reads that one snapshot. Calling it per slot
     * instead would run the whole content build fifty-four times a render.
     *
     * <p>Because the snapshot is recomputed on every render, {@link Menu#refresh(Player)} is all that
     * a live-updating menu needs — the upgrade countdowns re-derive themselves without the menu being
     * reopened under the player.
     *
     * @param render        produces this viewer's slot contents and click actions
     * @param configuredClick fallback for slots the render did not claim, i.e. ordinary config buttons
     */
    public Menu buildDynamic(MenuTemplate template,
                             String title,
                             Function<Player, Rendered> render,
                             SlotClickHandler configuredClick,
                             DynamicClickHandler dynamicClick) {
        MenuBuilder builder = Menu.builder(template.size() / 9)
                .setTitle(Text.legacy(title))
                .onRender((player, menu) -> menu.setState(player, STATE_RENDER, render.apply(player)));

        for (int index = 0; index < template.size(); index++) {
            builder.setSlot(row(index), column(index),
                    dynamicSlot(template, index, configuredClick, dynamicClick));
        }
        return builder.build();
    }

    /**
     * A slot backed by the render snapshot. Falls back to the configured slot's click effects when the
     * render registered no action for this index, which is how a menu mixes fixed buttons (Back, Close)
     * with generated content.
     */
    private Slot dynamicSlot(MenuTemplate template, int index,
                             SlotClickHandler configuredClick, DynamicClickHandler dynamicClick) {
        MenuSlot configured = template.slotAt(index);
        return Slot.builder((SlotProvider) (player, menu) -> {
                    Rendered rendered = menu.getState(player, STATE_RENDER);
                    return rendered == null ? null : rendered.items()[index];
                })
                .onLeftClick((SlotHandler) (event, slot, menu) ->
                        click(event, menu, index, configured, configuredClick, dynamicClick, false))
                .onRightClick((SlotHandler) (event, slot, menu) ->
                        click(event, menu, index, configured, configuredClick, dynamicClick, true))
                .build();
    }

    private void click(InventoryClickEvent event, Menu menu, int index, MenuSlot configured,
                       SlotClickHandler configuredClick, DynamicClickHandler dynamicClick,
                       boolean rightClick) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Rendered rendered = menu.getState(player, STATE_RENDER);
        BiConsumer<Player, Boolean> action = rendered == null ? null : rendered.actions().get(index);
        if (action != null) {
            dynamicClick.onClick(player, action, rightClick);
            return;
        }
        if (configured != null) {
            configuredClick.onClick(player, configured, rightClick && !configured.rightClick().isEmpty());
        }
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
