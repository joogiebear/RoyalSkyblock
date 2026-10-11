package com.mystipixel.royalskyblock.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The text that moved out of the code into lang.yml, rendered the way MessageManager does and compared
// with what the code used to send, so the shipped defaults show exactly what players and staff saw before.
class LangDefaultsTest {

    private static YamlConfiguration lang;

    @BeforeAll
    static void load() throws Exception {
        lang = LangKeysTest.lang();
    }

    // MessageManager.raw: the key's text with each %token% replaced in order
    private static String raw(String key, String... placeholders) {
        String value = lang.getString("messages." + key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return value;
    }

    private static String raw(Reason reason) {
        return raw(reason.key(), reason.placeholders());
    }

    private static String prefixed(String key, String... placeholders) {
        return lang.getString("messages.prefix") + raw(key, placeholders);
    }

    @Test
    void coopRefusalsMatchTheOldText() {
        Map<Reason, String> old = new LinkedHashMap<>();
        old.put(new Reason("coop.errors.not-online"), "That player isn't online.");
        old.put(new Reason("coop.errors.no-profile"), "You have no active profile.");
        old.put(new Reason("coop.errors.not-coop-profile", "gamemode", "solo", "profile", "Apple"),
                "You're on 'Apple' (solo). Switch to a Coop profile to invite members.");
        old.put(new Reason("coop.errors.invite-not-owner"), "Only the profile owner can invite members.");
        old.put(new Reason("coop.errors.invite-self"), "You can't invite yourself.");
        old.put(new Reason("coop.errors.already-member", "player", "Alex"), "Alex is already on this profile.");
        old.put(new Reason("coop.errors.full", "max", "4"), "This profile is full (4 members).");
        old.put(new Reason("coop.errors.not-on-coop"), "You're not on a Coop profile.");
        old.put(new Reason("coop.errors.kick-not-owner"), "Only the profile owner can kick members.");
        old.put(new Reason("coop.errors.no-member", "player", "Bob"), "No member named Bob.");
        old.put(new Reason("coop.errors.kick-owner"), "You can't kick the owner.");
        old.put(new Reason("coop.errors.kick-co-owner"), "Only the owner can remove a co-owner.");
        old.put(new Reason("coop.errors.kick-self"), "You can't kick yourself: use /is leave.");
        old.put(new Reason("coop.errors.owner-cant-leave"),
                "The owner can't leave: transfer ownership first (/is transfer) or delete the profile.");
        old.put(new Reason("coop.errors.not-member"), "You're not a member of this profile.");
        old.put(new Reason("coop.errors.already-co-owner", "player", "Alex"), "Alex is already a co-owner.");
        old.put(new Reason("coop.errors.not-co-owner", "player", "Alex"), "Alex isn't a co-owner.");
        old.put(new Reason("coop.errors.rank-not-owner"), "Only the owner can change member ranks.");
        old.put(new Reason("coop.errors.rank-self"), "You can't change your own rank.");
        old.put(new Reason("coop.errors.transfer-not-owner"), "Only the owner can transfer ownership.");
        old.put(new Reason("coop.errors.transfer-self"), "You already own this profile.");
        old.put(new Reason("coop.errors.transfer-profile-limit", "max", "3", "player", "Alex"),
                "Alex already owns the maximum number of profiles (3).");
        old.put(new Reason("profile.errors.limit", "max", "3"), "You've reached the profile limit (3).");
        old.put(new Reason("profile.errors.name-length"), "Profile names must be 1-16 characters.");
        old.put(new Reason("profile.errors.name-chars"), "Profile names may only use letters, digits, spaces, _ and -.");
        old.put(new Reason("profile.errors.name-taken", "name", "Apple"), "You already have a profile called Apple.");
        old.forEach((reason, text) -> assertEquals(text, raw(reason), reason.key()));

        assertEquals("&cYou can't kick the owner.", raw("coop.kick-error", "error", raw(new Reason("coop.errors.kick-owner"))));
        assertEquals("&aClick to accept", raw("coop.invite-accept-hover"));
        assertEquals("&cClick to decline", raw("coop.invite-deny-hover"));
        assertEquals("a former coop", raw("coop.former-coop"));
    }

    @Test
    void profileTextMatchesTheOldText() {
        assertEquals("&ayes", raw("profile.island-yes"));
        assertEquals("&cno", raw("profile.island-no"));
        assertEquals("yours", raw("profile.owner-yours"));
        assertEquals("Alex's", raw("profile.owner-named", "player", "Alex"));
        assertEquals("someone else's", raw("profile.owner-unknown"));
        assertEquals("Apple &7(coop)", raw("profile.label", "gamemode", "coop", "name", "Apple"));
        assertEquals("&cYou can only delete profiles you own: use &e/is leave &cto leave a coop.", raw("profile.delete-not-owner"));
        assertEquals("&cYou can't delete the profile you're on: switch to another first.", raw("profile.delete-active"));
        assertEquals("&cYou can't delete your only profile.", raw("profile.delete-only"));
        String banks = String.join(raw("profile.delete-bank-separator"),
                raw("profile.delete-bank-coop", "amount", "$5.00"),
                raw("profile.delete-bank-personal", "amount", "$1.00", "player", "Alex"));
        assertEquals("&cEmpty the banks on this profile before deleting it: &fthe coop bank ($5.00)&c, &fAlex's personal bank ($1.00)",
                raw("profile.delete-banks", "banks", banks));
    }

    @Test
    void bankResultsMatchTheOldText() {
        Map<String, String> old = new LinkedHashMap<>();
        old.put("unavailable", "&cThe bank is unavailable (no economy plugin).");
        old.put("interest-unavailable", "&cThe bank is unavailable.");
        old.put("account.deposit.bank-full", "&cThe bank is full. Upgrade it to store more.");
        old.put("account.deposit.not-enough-money", "&cYou don't have enough money to deposit that.");
        old.put("deposit-failed", "&cDeposit failed.");
        old.put("account.deposit.refunded", "&cDeposit could not be saved; your money was refunded.");
        old.put("account.withdraw.not-enough-money", "&cThe bank doesn't have that much money.");
        old.put("purse-full", "&cYour purse couldn't take that much (is it at its limit?). Nothing was withdrawn.");
        old.put("withdraw.reverted", "&cWithdrawal could not be saved and was reverted; please try again.");
        old.put("account.upgrade.max-level", "&eThe bank is already at the maximum level.");
        old.put("upgrade-payment-failed", "&cUpgrade payment failed.");
        old.put("upgrade.refunded", "&cUpgrade could not be saved. Your money was refunded; please try again.");
        old.put("interest-not-ready", "&eInterest isn't ready yet.");
        old.put("no-interest", "&eNo interest was earned (empty bank).");
        old.put("interest-full", "&eYour bank is full, so no interest could be paid.");
        old.put("interest.not-saved", "&cInterest could not be saved; please try again.");
        old.forEach((key, text) -> assertEquals(text, raw("bank.result." + key), key));

        assertEquals("&cAmount must be at least $0.01.", raw("bank.result.amount.too-small", "amount", "$0.01"));
        assertEquals("&cYou need $500.00 to upgrade.", raw("bank.result.account.upgrade.not-enough-money", "cost", "$500.00"));
        String items = String.join(raw("bank.result.format.item-separator"),
                raw("bank.result.format.item-have", "have", "3", "item", "64x Diamond"),
                raw("bank.result.format.item-have", "have", "0", "item", "1x Nether Star"));
        assertEquals("&cMissing upgrade items: &f64x Diamond (have 3), 1x Nether Star (have 0)",
                raw("bank.result.upgrade.missing-items", "items", items));

        assertEquals("Steve deposited", raw("bank.notes.account-deposit", "player", "Steve"));
        assertEquals("Steve withdrew", raw("bank.notes.account-withdraw", "player", "Steve"));
        assertEquals("Upgraded to Gold", raw("bank.notes.upgrade", "level", "Gold"));
        assertEquals("Interest", raw("bank.notes.interest"));
        assertEquals("Steve left Apple", raw("bank.notes.coop-payout", "profile", "Apple", "player", "Steve"));
        assertEquals("Steve left Apple (purse refused)", raw("bank.notes.purse-refused", "note", "Steve left Apple"));
        assertEquals(lang.getString("messages.prefix") + "&cThe bank is full. Upgrade it to store more.",
                prefixed("bank.result.account.deposit.bank-full"));
    }

    @Test
    void commandDescriptionsMatchTheOldText() {
        assertEquals("RoyalSkyblock island command.", raw("command-descriptions.island"));
        assertEquals("Open your bank (personal + coop).", raw("command-descriptions.bank"));
    }

    @Test
    void adminOutputMatchesTheOldText() {
        assertEquals(List.of(
                "&8» &e/is admin status &7- dependency & config health",
                "&8» &e/is admin split-content [confirm] &7- upgrades.yml/perks.yml to one file each",
                "&8» &e/is admin mobspawn <status|test <family> [level]> &7- island mob spawning",
                "&8» &e/is admin border <blue|red|green|off> &7- island border colour",
                "&8» &e/is admin testworld &7- ASP world round-trip diagnostic",
                "&8» &e/is admin loadtest <count> [holdSecs] &7- island load/unload + heap benchmark",
                "&8» &e/is admin schematic save <name> &7- save your WorldEdit selection",
                "&8» &e/is admin upgrade <key> <tier> &7- set an upgrade tier instantly",
                "&8» &e/is admin trash <list|restore <archive> <player>> &7- deleted-island archives",
                "&8» &e/is admin orphans [purge] &7- worlds no island row references",
                "&8» &e/is admin npc-open <player> <command> &7- run a command for a player (hub NPCs)"),
                lang.getStringList("messages.admin.help"));

        // status: dep() and check() lines
        assertEquals("&7Islands (ASP): &a✔ ready &7(source: file)", raw("admin.status.ready-detail",
                "detail", raw("admin.status.islands-ready", "source", "file"), "name", raw("admin.status.islands")));
        assertEquals("&7Economy (Vault): &a✔ ready", raw("admin.status.ready", "name", raw("admin.status.economy")));
        assertEquals("", raw("admin.status.economy-ready"));
        assertEquals("&7Economy (Vault): &c✘ not found: bank & coin costs off", raw("admin.status.missing",
                "detail", raw("admin.status.economy-missing"), "name", raw("admin.status.economy")));
        assertEquals("&7Bank: &a✔ ready &7(4 levels)", raw("admin.status.ready-detail",
                "detail", raw("admin.status.bank-ready", "count", "4"), "name", raw("admin.status.bank")));
        assertEquals("&7Schematics: &c✘ built-in generator", raw("admin.status.missing",
                "detail", raw("admin.status.schematics-missing"), "name", raw("admin.status.schematics")));
        assertEquals("&7Progression (eco): &a✔ ready &7(per-profile)", raw("admin.status.ready-detail",
                "detail", raw("admin.status.progression-ready"), "name", raw("admin.status.progression")));
        assertEquals("  &a✔ &7spawn world 'world' loaded", raw("admin.status.check-ok",
                "message", raw("admin.status.spawn-ok", "world", "world")));
        assertEquals("  &c✘ &fno bank levels: check bank.yml", raw("admin.status.check-failed",
                "message", raw("admin.status.bank-levels-missing")));
        assertEquals("&7Perks: &a✔ on &7(8)", raw("admin.status.perks-on", "count", "8"));
        assertEquals("&7Perks: &8off (optional)", raw("admin.status.perks-off"));

        assertEquals("&8» &e&lIsland trash &7- 3 archive(s), kept 7 day(s)", raw("admin.trash.header", "count", "3", "days", "7"));
        assertEquals("  &8· &fisland_x.slime &7(12 KB, Oct 9 14:02)",
                raw("admin.trash.line", "size", "12", "date", "Oct 9 14:02", "file", "island_x.slime"));
        assertEquals("&aRestored &fa.slime&a as Steve's island. &7/is home takes them there; upgrades and level start "
                        + "fresh (they lived in the deleted rows): the level recalculates on &e/is level recalc&7.",
                raw("admin.trash.restored", "player", "Steve", "archive", "a.slime"));
        assertEquals("&aPurged &f2&a orphaned world(s) into the trash. &c1 failed: see console.", raw("admin.orphans.purged",
                "failed", raw("admin.orphans.purged-failures", "count", "1"), "count", "2"));
        assertEquals("&aPurged &f2&a orphaned world(s) into the trash.", raw("admin.orphans.purged", "failed", "", "count", "2"));
        assertEquals("&6Island mobs &7- provider &fecomobs &7(&aavailable&7), &f5 &7families, enabled=&ftrue",
                raw("admin.mobspawn.status", "availability", raw("admin.mobspawn.available"), "families", "5",
                        "enabled", "true", "provider", "ecomobs"));
        assertEquals("&7Border colour: &fblue  &8· &e/is admin border <blue|red|green|off>", raw("admin.border.current", "color", "blue"));
        assertEquals("&aSet &esize &ato tier &e3 &7(value 25.0).", raw("admin.upgrade.set", "tier", "3", "value", "25.0", "key", "size"));
        assertEquals("&e[loadtest] &7loaded 5/5 in 900ms (180ms avg/island)", raw("admin.loadtest.report", "message",
                raw("admin.loadtest.loaded", "loaded", "5", "count", "5", "ms", "900", "average", "180")));
        assertEquals("heap: +12.0MB total, ~2.4MB per island", raw("admin.loadtest.heap", "total", "12.0MB", "per_island", "2.4MB"));
        assertEquals("DONE: 5 islands, 4000ms total (incl. hold), teardown 300ms, leaked 0, clean.", raw("admin.loadtest.done",
                "result", raw("admin.loadtest.done-clean"), "count", "5", "total", "4000", "ms", "300", "leaked", "0"));
        assertEquals("&aSaved &eschematics/spawn.schem&a. Use it by setting &eisland.starter.schematic: spawn &ain config.yml.",
                raw("admin.schematic.saved", "name", "spawn"));
        assertEquals("&cMake a WorldEdit selection first (//wand, then left/right-click two corners).",
                raw("admin.schematic.failed", "error", raw("admin.schematic.errors.no-selection")));
        assertEquals("Couldn't save schematic: boom", raw("admin.schematic.errors.save-failed", "error", "boom"));
        assertEquals("WorldEdit or FAWE isn't installed.", raw("admin.schematic.errors.not-installed"));
        assertEquals("&a[diag] deleted 'rsb_diagnostic': ASP round-trip succeeded. ✔",
                raw("admin.testworld.deleted", "world", "rsb_diagnostic"));
        assertEquals("MMM d HH:mm", raw("admin.trash.date-format"));
    }
}
