package com.mystipixel.royalskyblock.gui;

import com.mystipixel.royalskyblock.gui.menu.MenuText;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.island.IslandSetting;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The menu text now comes from the bundled gui/*.yml and lang.yml. These render items the way
// GuiManager does and compare them with what the code used to build, so the defaults can't drift
// from what players saw before.
class MenuTextDefaultsTest {

    // every path GuiManager reads from each menu file
    private static final Map<String, List<String>> READS = Map.ofEntries(
            Map.entry("profile/profiles", List.of("entry.name", "entry.lore", "entry.active-tag",
                    "entry.island-yes", "entry.island-no", "entry.action-active", "entry.action-switch")),
            Map.entry("island/upgrades", List.of("entry.name", "entry.lore", "entry.lore-skippable",
                    "entry.lore-maxed", "entry.lore-upgrading", "entry.lore-upgrading-skippable", "entry.effect",
                    "entry.wait", "effect-labels.radius", "effect-labels.guest-slots",
                    "effect-labels.guest-slots-plural", "effect-labels.coop-slots", "effect-labels.coop-slots-plural",
                    "effect-labels.generator", "effect-labels.minions", "effect-labels.minions-plural")),
            Map.entry("island/settings", List.of("entry.name", "entry.lore", "entry.state-on", "entry.state-off",
                    "entry.action-enable", "entry.action-disable", "entry.action-locked")),
            Map.entry("island/visit", List.of("entry.name", "entry.lore")),
            Map.entry("coop/coop", List.of("header.item", "header.lore", "header.row", "entry.name", "entry.lore",
                    "entry.lore-manageable")),
            Map.entry("coop/coop-invite", List.of("entry.name", "entry.lore")),
            Map.entry("coop/coop-member", List.of("header.name", "header.lore", "header.row",
                    "buttons.promote.item", "buttons.promote.lore", "buttons.demote.item", "buttons.demote.lore",
                    "buttons.transfer.item", "buttons.transfer.lore", "buttons.kick.item", "buttons.kick.lore")),
            Map.entry("bank/coop-bank-txns", List.of("back.item", "back.lore", "empty.item", "empty.lore",
                    "entry.lore", "entry.lore-note", "types.deposit", "types.withdraw", "types.upgrade",
                    "types.interest", "types.other")),
            Map.entry("level/level", List.of("entry.name", "entry.lore", "empty.item", "empty.lore",
                    "perks-button.item", "perks-button.lore")),
            Map.entry("level/top", List.of("entry.name", "entry.lore")),
            Map.entry("level/perks", List.of("entry-unlocked.name", "entry-unlocked.lore", "entry-locked.item",
                    "entry-locked.lore", "disabled.item", "disabled.lore", "empty.item", "empty.lore")));

    private static final List<String> BANK_READS = List.of("header.item", "header.lore", "header.lore-unavailable",
            "buttons.deposit.item", "buttons.deposit.lore", "buttons.deposit.positions",
            "buttons.withdraw.item", "buttons.withdraw.lore", "buttons.withdraw.positions",
            "buttons.deposit-all.item", "buttons.deposit-all.lore", "buttons.withdraw-all.item",
            "buttons.withdraw-all.lore", "buttons.claim-interest.item", "buttons.claim-interest.lore",
            "buttons.interest-waiting.item", "buttons.interest-waiting.lore", "buttons.upgrade.item",
            "buttons.upgrade.lore", "buttons.upgrade.lore-items", "buttons.maxed.item", "buttons.maxed.lore",
            "buttons.transactions.item", "buttons.transactions.lore");

    private static MenuText text(String path) throws IOException, InvalidConfigurationException {
        YamlConfiguration bundled = MenuLayoutTest.menu(path);
        return new MenuText(new YamlConfiguration(), bundled, bundled.getInt("rows", 5) * 9);
    }

    private static YamlConfiguration lang() throws IOException, InvalidConfigurationException {
        YamlConfiguration lang = new YamlConfiguration();
        lang.loadFromString(MenuLayoutTest.read("lang.yml"));
        return lang;
    }

    private static Map<String, String> ph(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static List<String> lore(MenuText text, String path, Map<String, String> ph) {
        return MenuText.fill(text.lines(path), ph, Map.of());
    }

    @Test
    void everyPathTheCodeReadsIsBundled() throws Exception {
        for (Map.Entry<String, List<String>> menu : READS.entrySet()) {
            YamlConfiguration cfg = MenuLayoutTest.menu(menu.getKey());
            for (String path : menu.getValue()) {
                assertTrue(cfg.isSet(path), "gui/" + menu.getKey() + ".yml is missing " + path);
            }
        }
        for (String bank : List.of("bank/bank-personal", "bank/coop-bank")) {
            YamlConfiguration cfg = MenuLayoutTest.menu(bank);
            for (String path : BANK_READS) {
                assertTrue(cfg.isSet(path), "gui/" + bank + ".yml is missing " + path);
            }
        }
        YamlConfiguration settings = MenuLayoutTest.menu("island/settings");
        for (IslandSetting setting : IslandSetting.values()) {
            for (String field : List.of("icon", "name", "description")) {
                assertTrue(settings.isSet("settings." + setting.key() + "." + field),
                        "gui/island/settings.yml is missing settings." + setting.key() + "." + field);
            }
        }
        YamlConfiguration lang = lang();
        for (String unit : List.of("instant", "days", "hours", "minutes", "seconds", "unknown")) {
            assertTrue(lang.isSet("messages.format.duration." + unit), "lang.yml is missing format.duration." + unit);
        }
        for (IslandRole role : IslandRole.values()) {
            String key = "messages.format.role." + role.name().toLowerCase(Locale.ROOT).replace('_', '-');
            assertTrue(lang.isSet(key), "lang.yml is missing " + key);
        }
        assertTrue(lang.isSet("messages.format.online"));
        assertTrue(lang.isSet("messages.format.offline"));
    }

    @Test
    void langHasNoDuplicateKeys() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        assertDoesNotThrow(() -> new Yaml(options).load(MenuLayoutTest.read("lang.yml")));
    }

    @Test
    void anOldServerFileFallsBackToTheBundledText() throws Exception {
        YamlConfiguration bundled = MenuLayoutTest.menu("level/top");
        YamlConfiguration old = new YamlConfiguration();
        old.set("title", "&8Old title");
        old.set("entry.name", "&b#%rank% %owner%");
        MenuText text = new MenuText(old, bundled, 54);
        assertEquals("&b#%rank% %owner%", text.text("entry.name"), "the server's own text wins");
        assertEquals(bundled.getStringList("entry.lore"), text.lines("entry.lore"), "a missing key uses the bundled one");
    }

    @Test
    void fillIsOnePassAndExpandsListLines() {
        assertEquals("a %b% c", MenuText.fill("%x% c", ph("x", "a %b%", "b", "WRONG")));
        assertEquals("100% done", MenuText.fill("100% done", ph("done", "x")));
        assertEquals(List.of("&7x", "one", "two", "&7y"),
                MenuText.fill(List.of("&7x", "%description%", "&7y"), Map.of(), Map.of("description", List.of("one", "two"))));
    }

    @Test
    void profileEntryMatchesTheOldText() throws Exception {
        MenuText text = text("profile/profiles");
        for (boolean active : new boolean[]{true, false}) {
            for (boolean island : new boolean[]{true, false}) {
                Map<String, String> ph = ph("name", "Apple", "gamemode_name", "&aSolo",
                        "gamemode_description", "&7Just you.",
                        "active", active ? text.text("entry.active-tag") : "",
                        "island", text.text(island ? "entry.island-yes" : "entry.island-no"),
                        "action", text.text(active ? "entry.action-active" : "entry.action-switch"));
                assertEquals("&e&lApple &r&aSolo" + (active ? " &a(active)" : ""),
                        MenuText.fill(text.text("entry.name"), ph));
                assertEquals(List.of("&7Just you.", "&7Island: " + (island ? "&ayes" : "&cno"), "",
                        active ? "&8You're on this profile." : "&eClick to switch!"), lore(text, "entry.lore", ph));
            }
        }
    }

    @Test
    void upgradeEntryMatchesTheOldText() throws Exception {
        MenuText text = text("island/upgrades");
        Map<String, String> ph = ph("name", "&bIsland Size", "description", "Grow your border.", "tier", "1",
                "max_tier", "5", "next_tier", "2",
                "effect", MenuText.fill(text.text("entry.effect"), ph("label", "41x41")),
                "cost", "500 coins",
                "wait", MenuText.fill(text.text("entry.wait"), ph("time", "1h 30m")),
                "skip_cost", "50 gems", "target_tier", "2", "time_left", "4m 2s");
        assertEquals("&bIsland Size &7(Tier 1/5)", MenuText.fill(text.text("entry.name"), ph));
        assertEquals(List.of("&7Grow your border.", "", "&7Next: tier 2 &8(41x41)", "&7Cost: &e500 coins &7+ 1h 30m wait",
                "&7Skip cost: &e50 gems", "", "&eClick to upgrade &7(right-click after to skip)"),
                lore(text, "entry.lore-skippable", ph));
        ph.put("effect", "");
        ph.put("wait", "");
        assertEquals(List.of("&7Grow your border.", "", "&7Next: tier 2", "&7Cost: &e500 coins", "", "&eClick to upgrade"),
                lore(text, "entry.lore", ph));
        assertEquals(List.of("&7Grow your border.", "", "&a✔ Maxed out."), lore(text, "entry.lore-maxed", ph));
        assertEquals(List.of("&7Grow your border.", "", "&e⏳ Upgrading to tier 2 &7- 4m 2s left",
                "&7Skip now: &e50 gems", "", "&eRight-click to skip the wait."),
                lore(text, "entry.lore-upgrading-skippable", ph));
        assertEquals(List.of("&7Grow your border.", "", "&e⏳ Upgrading to tier 2 &7- 4m 2s left"),
                lore(text, "entry.lore-upgrading", ph));

        Map<String, String> one = ph("value", "1", "size", "3");
        Map<String, String> three = ph("value", "3", "size", "7");
        assertEquals("7x7", MenuText.fill(text.text("effect-labels.radius"), three));
        assertEquals("+1 guest", MenuText.fill(text.text("effect-labels.guest-slots"), one));
        assertEquals("+3 guests", MenuText.fill(text.text("effect-labels.guest-slots-plural"), three));
        assertEquals("+1 coop slot", MenuText.fill(text.text("effect-labels.coop-slots"), one));
        assertEquals("+3 coop slots", MenuText.fill(text.text("effect-labels.coop-slots-plural"), three));
        assertEquals("Tier 3 ores", MenuText.fill(text.text("effect-labels.generator"), three));
        assertEquals("+1 minion slot", MenuText.fill(text.text("effect-labels.minions"), one));
        assertEquals("+3 minion slots", MenuText.fill(text.text("effect-labels.minions-plural"), three));
    }

    @Test
    void settingEntriesMatchTheOldText() throws Exception {
        MenuText text = text("island/settings");
        assertEquals("oak_door", text.text("settings.visitors-allowed.icon"));
        assertEquals("book", text.text("settings.listed.icon"));
        assertEquals("zombie_head", text.text("settings.mob-spawns.icon"));
        Map<String, String> ph = ph("name", text.text("settings.visitors-allowed.name"),
                "description", text.text("settings.visitors-allowed.description"),
                "state", text.text("entry.state-on"), "action", text.text("entry.action-disable"));
        assertEquals("&bVisitors &a&lON", MenuText.fill(text.text("entry.name"), ph));
        assertEquals(List.of("&7Allow players who aren't members to visit your island.", "", "&eClick to disable"),
                lore(text, "entry.lore", ph));
        assertEquals("&aVisit Listing", text.text("settings.listed.name"));
        assertEquals("&7Show this island in the public /visit browser.",
                MenuText.fill("&7%description%", ph("description", text.text("settings.listed.description"))));
        assertEquals("&cMob Spawns", text.text("settings.mob-spawns.name"));
        assertEquals("Spawn combat mobs on your island at night.", text.text("settings.mob-spawns.description"));
        assertEquals(" &c&lOFF", " " + text.text("entry.state-off"));
        assertEquals("&eClick to enable", text.text("entry.action-enable"));
        assertEquals("&8Only the owner can change this.", text.text("entry.action-locked"));
    }

    @Test
    void headsAndRanksMatchTheOldText() throws Exception {
        MenuText visit = text("island/visit");
        Map<String, String> ph = ph("owner", "Steve", "gamemode", "coop", "level", "12", "members", "3",
                "rank", "1", "member", "Alex", "role", "co owner", "status", "&aonline", "name", "Alex",
                "profile", "Apple", "max", "4");
        assertEquals("&aSteve&7's Island", MenuText.fill(visit.text("entry.name"), ph));
        assertEquals(List.of("&7Gamemode: &fcoop", "&7Island level: &f12", "&7Members: &f3", "", "&eClick to visit!"),
                lore(visit, "entry.lore", ph));

        MenuText top = text("level/top");
        assertEquals("&e&l#1 &aSteve", MenuText.fill(top.text("entry.name"), ph));
        assertEquals(List.of("&7Level: &e12", "&7Gamemode: &fcoop", "&7Members: &f3"), lore(top, "entry.lore", ph));

        MenuText coop = text("coop/coop");
        assertEquals("cyan_bed name:\"&6&lApple &7Coop\"", MenuText.fill(coop.text("header.item"), ph));
        assertEquals(List.of("&7Owner: &fSteve", "&7Members: &f3&7/&f4", "", "&7Click a member to manage them."),
                lore(coop, "header.lore", ph));
        assertEquals(4, coop.slot("header"));
        assertEquals("&aAlex", MenuText.fill(coop.text("entry.name"), ph));
        assertEquals(List.of("&7Role: &fco owner", "&7Status: &aonline", "", "&cClick to remove from the island."),
                lore(coop, "entry.lore-manageable", ph));

        MenuText member = text("coop/coop-member");
        assertEquals(4, member.slot("header"));
        assertEquals(List.of("&7Role: &fco owner", "&7Status: &aonline"), lore(member, "header.lore", ph));
        assertEquals(11, member.slot("buttons.promote"));
        assertEquals(11, member.slot("buttons.demote"));
        assertEquals(13, member.slot("buttons.transfer"));
        assertEquals(15, member.slot("buttons.kick"));
        assertEquals("lime_dye name:\"&a&lPromote to Co-Owner\"", member.text("buttons.promote.item"));
        assertEquals(List.of("&7Let Alex invite, kick,", "&7and manage the island.", "", "&eClick to promote!"),
                lore(member, "buttons.promote.lore", ph));
        assertEquals(List.of("&7Make Alex the owner.", "&cYou'll become a co-owner.", "",
                "&7Left-click: details", "&eRight-click: confirm transfer"), lore(member, "buttons.transfer.lore", ph));
        assertEquals(List.of("&7Kick Alex from", "&7the coop.", "", "&eClick to remove!"),
                lore(member, "buttons.kick.lore", ph));

        MenuText invite = text("coop/coop-invite");
        assertEquals("&aAlex", MenuText.fill(invite.text("entry.name"), ph));
    }

    @Test
    void bankButtonsMatchTheOldText() throws Exception {
        for (String menu : List.of("bank/bank-personal", "bank/coop-bank")) {
            MenuText text = text(menu);
            assertEquals(4, text.slot("header"));
            assertEquals(List.of(19, 21, 23), text.slots("buttons.deposit.positions"));
            assertEquals(List.of(28, 30, 32), text.slots("buttons.withdraw.positions"));
            assertEquals(25, text.slot("buttons.deposit-all"));
            assertEquals(34, text.slot("buttons.withdraw-all"));
            assertEquals(37, text.slot("buttons.claim-interest"));
            assertEquals(37, text.slot("buttons.interest-waiting"));
            assertEquals(39, text.slot("buttons.upgrade"));
            assertEquals(39, text.slot("buttons.maxed"));
            assertEquals(41, text.slot("buttons.transactions"));

            Map<String, String> ph = ph("profile", "Apple", "balance", "$1,000.00", "level", "2", "level_name", "Silver",
                    "max_balance", "$50,000.00", "next_interest", "$10.00", "amount", "1,000", "time", "3h 2m",
                    "next_level", "Gold", "cost", "$5,000.00", "items", "64x Diamond");
            List<String> header = List.of("&7Balance: &e$1,000.00", "&7Level: &f2 &8- Silver",
                    "&7Max balance: &f$50,000.00", "&7Next interest: &f$10.00");
            assertEquals(header, lore(text, "header.lore", ph));
            List<String> unavailable = new ArrayList<>(header);
            unavailable.add("");
            unavailable.add("&cNo economy plugin: banking disabled.");
            assertEquals(unavailable, lore(text, "header.lore-unavailable", ph));
            assertEquals("lime_dye name:\"&a&lDeposit 1,000\"", MenuText.fill(text.text("buttons.deposit.item"), ph));
            assertEquals("gold_nugget name:\"&6&lWithdraw 1,000\"", MenuText.fill(text.text("buttons.withdraw.item"), ph));
            assertEquals(List.of("&7Next interest in &f3h 2m&7."), lore(text, "buttons.interest-waiting.lore", ph));
            assertEquals(List.of("&7Next: &fGold", "&7Cost: &e$5,000.00", "&7Items: &f64x Diamond",
                    "&7New max balance: &f$50,000.00", "", "&eClick to upgrade!"), lore(text, "buttons.upgrade.lore-items", ph));
            assertEquals(List.of("&7Next: &fGold", "&7Cost: &e$5,000.00",
                    "&7New max balance: &f$50,000.00", "", "&eClick to upgrade!"), lore(text, "buttons.upgrade.lore", ph));
            assertEquals("nether_star name:\"&a&lBank Maxed\"", text.text("buttons.maxed.item"));
        }
        assertEquals("gold_ingot name:\"&6&lPersonal Bank\"", text("bank/bank-personal").text("header.item"));
        assertEquals("gold_ingot name:\"&6&lApple Coop Bank\"",
                MenuText.fill(text("bank/coop-bank").text("header.item"), ph("profile", "Apple")));

        MenuText txns = text("bank/coop-bank-txns");
        assertEquals(48, txns.slot("back"));
        Map<String, String> ph = ph("type", "PAYOUT", "amount", "$5.00", "balance", "$0.00", "note", "Steve left Apple");
        assertEquals("paper name:\"&7PAYOUT\"", MenuText.fill(txns.text("types.other"), ph));
        assertEquals("lime_dye name:\"&aDeposit\"", txns.text("types.deposit"));
        assertEquals(List.of("&7Amount: &e$5.00", "&7Balance after: &f$0.00", "&8Steve left Apple"),
                lore(txns, "entry.lore-note", ph));
        assertEquals(List.of("&7Amount: &e$5.00", "&7Balance after: &f$0.00"), lore(txns, "entry.lore", ph));
    }

    @Test
    void levelAndPerkEntriesMatchTheOldText() throws Exception {
        MenuText level = text("level/level");
        Map<String, String> ph = ph("block", "Diamond Block", "count", "4", "each", "50", "total", "200",
                "name", "&bHaste", "required_level", "10");
        assertEquals("&aDiamond Block", MenuText.fill(level.text("entry.name"), ph));
        assertEquals(List.of("&7Count: &f4", "&7Each: &e50 &7pts", "&7Total: &e200 &7pts"), lore(level, "entry.lore", ph));
        assertEquals(40, level.slot("perks-button"));

        MenuText perks = text("level/perks");
        Map<String, List<String>> description = Map.of("description", List.of("&7Mine faster", "&7on your island."));
        assertEquals("&bHaste &a✔", MenuText.fill(perks.text("entry-unlocked.name"), ph));
        assertEquals(List.of("&7Mine faster", "&7on your island.", "", "&7Requires island level &f10", "&a✔ Unlocked"),
                MenuText.fill(perks.lines("entry-unlocked.lore"), ph, description));
        assertEquals("gray_dye name:\"&bHaste &8(locked)\"", MenuText.fill(perks.text("entry-locked.item"), ph));
        assertEquals(List.of("&7Mine faster", "&7on your island.", "", "&7Requires island level &f10",
                "&c✘ Locked: reach level 10"), MenuText.fill(perks.lines("entry-locked.lore"), ph, description));
    }

    @Test
    void durationsMatchTheOldFormat() throws Exception {
        YamlConfiguration lang = lang();
        for (long seconds : new long[]{-5, 0, 1, 59, 60, 61, 3_599, 3_600, 3_661, 86_399, 86_400, 90_061, 200_000}) {
            String rendered = GuiManager.formatDuration(seconds, (unit, value) ->
                    lang.getString("messages.format.duration." + unit, "").replace("%value%", String.valueOf(value)));
            assertEquals(oldFormatDuration(seconds), rendered, seconds + "s");
        }
        assertEquals("0s", lang.getString("messages.format.duration.seconds").replace("%value%", "0"));
        assertEquals("?", lang.getString("messages.format.duration.unknown"));
        assertEquals("co owner", lang.getString("messages.format.role.co-owner"));
        assertEquals("&aonline", lang.getString("messages.format.online"));
        assertEquals("&8offline", lang.getString("messages.format.offline"));
        assertFalse(lang.getString("messages.format.role.owner").isEmpty());
    }

    // the formatter GuiManager had before the units moved to lang.yml
    private static String oldFormatDuration(long seconds) {
        if (seconds <= 0) {
            return "instant";
        }
        long d = seconds / 86_400, h = (seconds % 86_400) / 3_600, m = (seconds % 3_600) / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 && d == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
