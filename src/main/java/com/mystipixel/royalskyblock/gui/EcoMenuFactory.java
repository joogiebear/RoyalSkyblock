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
 * Builds an eco {@link Menu} from a {@link MenuTemplate}, so slots go through eco's item pipeline
 * (EcoItems lore and rarity render correctly).
 *
 * <p>{@link MenuSlot#index()} is a 0-based inventory index while eco's {@code setSlot} is 1-based
 * row/column; {@link #row}/{@link #column} are the only place that conversion happens. Slots use
 * {@link Slot#builder(Function)} so {@code %token%} placeholders resolve per viewer on each render.
 */
public final class EcoMenuFactory {

    /** What to run when a configured slot is clicked. */
    @FunctionalInterface
    public interface SlotClickHandler {
        void onClick(Player player, MenuSlot slot, boolean rightClick);
    }

    /**
     * What to run when a code-registered (data-driven) slot is clicked. Unlike a configured slot, a
     * dynamic slot always sounds and its action must be deferred off the click event.
     */
    @FunctionalInterface
    public interface DynamicClickHandler {
        /**
         * @param configured the config slot at that index, or null when the mask left it empty. Generated
         *                   content inherits its sound.
         */
        void onClick(Player player, BiConsumer<Player, Boolean> action, boolean rightClick,
                     MenuSlot configured);
    }

    private static final String STATE_RENDER = "royalskyblock_render";

    private final EcoHook eco;

    public EcoMenuFactory(EcoHook eco) {
        this.eco = eco;
    }

    /**
     * Convert a template into a live eco menu.
     *
     * @param template     the parsed {@code gui/*.yml}
     * @param title        the menu title, already {@code %token%}-substituted (eco fixes it at build time)
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

    // Not eco's FillerMask: the template keeps only the filler item and content slots, not the raw
    // pattern. Content slots stay empty for dynamic menus to fill.
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
        // SlotProvider, not the Function<Player, ItemStack> overload: eco marks that one for removal
        SlotBuilder builder = Slot.builder((SlotProvider) (player, menu) ->
                slot.item().build(eco, placeholders.apply(player), slot.lore()));

        // right-click falls through to the left-click effects when a slot declares none of its own
        builder.onLeftClick((event, clicked) -> onClick.onClick((Player) event.getWhoClicked(), slot, false));
        builder.onRightClick((event, clicked) -> onClick.onClick((Player) event.getWhoClicked(), slot,
                !slot.rightClick().isEmpty()));
        return builder.build();
    }

    /**
     * Build a data-driven menu, where slot contents are computed per viewer rather than read from config.
     * {@code render} runs once per render pass and every slot reads that snapshot, so
     * {@link Menu#refresh(Player)} is all a live-updating menu needs.
     *
     * @param render          produces this viewer's slot contents and click actions
     * @param configuredClick fallback for slots the render did not claim, i.e. ordinary config buttons
     */
    public Menu buildDynamic(MenuTemplate template,
                             String title,
                             Function<Player, MenuCanvas> render,
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

    // Falls back to the configured slot's click effects when the render registered no action for this
    // index, so a menu can mix fixed buttons (Back, Close) with generated content.
    private Slot dynamicSlot(MenuTemplate template, int index,
                             SlotClickHandler configuredClick, DynamicClickHandler dynamicClick) {
        MenuSlot configured = template.slotAt(index);
        return Slot.builder((SlotProvider) (player, menu) -> {
                    MenuCanvas canvas = menu.getState(player, STATE_RENDER);
                    return canvas == null ? null : canvas.item(index);
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
        MenuCanvas canvas = menu.getState(player, STATE_RENDER);
        BiConsumer<Player, Boolean> action = canvas == null ? null : canvas.action(index);
        if (action != null) {
            dynamicClick.onClick(player, action, rightClick, configured);
            return;
        }
        if (configured != null) {
            configuredClick.onClick(player, configured, rightClick && !configured.rightClick().isEmpty());
        }
    }

    // 0-based inventory index to eco's 1-based row; package-private for the round-trip test
    static int row(int index) {
        return index / 9 + 1;
    }

    // 0-based inventory index to eco's 1-based column; package-private for the round-trip test
    static int column(int index) {
        return index % 9 + 1;
    }
}
