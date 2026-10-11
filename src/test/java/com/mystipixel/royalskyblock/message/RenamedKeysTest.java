package com.mystipixel.royalskyblock.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RenamedKeysTest {

    private static final String NEW = "bank.result.account.deposit.bank-full";
    private static final String OLD = "bank.result.full";
    private static final String SHIPPED = "&cThe bank is full. Upgrade it to store more.";

    private static YamlConfiguration bundled;

    @BeforeAll
    static void load() throws Exception {
        bundled = LangKeysTest.lang();
    }

    private static Function<String, String> bundledText() {
        return key -> bundled.getString("messages." + key);
    }

    private static String resolve(String key, Map<String, String> server) {
        return RenamedKeys.resolve(key, server::get, bundledText());
    }

    @Test
    void customisedOldKeyWinsOverTheDefaultEcoAdded() {
        Map<String, String> server = Map.of(OLD, "&cVault full!", NEW, SHIPPED);

        assertEquals("&cVault full!", resolve(NEW, server));
    }

    @Test
    void oldKeyIsUsedBeforeEcoHasAddedTheNewOne() {
        assertEquals("&cVault full!", resolve(NEW, Map.of(OLD, "&cVault full!")));
    }

    @Test
    void editedNewKeyWinsOverTheOldOne() {
        Map<String, String> server = Map.of(OLD, "&cVault full!", NEW, "&cNo room.");

        assertEquals("&cNo room.", resolve(NEW, server));
    }

    @Test
    void newServerUsesTheNewKey() {
        assertEquals(SHIPPED, resolve(NEW, Map.of(NEW, SHIPPED)));
    }

    @Test
    void missingEverywhereFallsBackToTheBundledText() {
        assertEquals(SHIPPED, resolve(NEW, Map.of()));
    }

    @Test
    void keysThatWereNotRenamedReadOnlyTheServerFile() {
        assertEquals("x", RenamedKeys.resolve("bank.deposited", Map.of("bank.deposited", "x")::get, bundledText()));
        assertNull(RenamedKeys.resolve("bank.deposited", Map.<String, String>of()::get, k -> "bundled"));
    }

    @Test
    void anUpgradedServerWithUneditedFilesShowsTheSameTextAsBefore() throws IOException {
        YamlConfiguration before = previousLang();
        Map<String, String> server = new HashMap<>();
        RenamedKeys.OLD_BY_NEW.forEach((newKey, oldKey) -> {
            String oldText = before.getString("messages." + oldKey);
            assertNotNull(oldText, oldKey + " was not in the previous lang.yml");
            server.put(oldKey, oldText);
            server.put(newKey, bundled.getString("messages." + newKey));   // what eco adds
        });

        RenamedKeys.OLD_BY_NEW.forEach((newKey, oldKey) ->
                assertEquals(before.getString("messages." + oldKey), resolve(newKey, server), newKey));
    }

    @Test
    void theNewKeysShipTheOldText() throws IOException {
        YamlConfiguration before = previousLang();
        RenamedKeys.OLD_BY_NEW.forEach((newKey, oldKey) ->
                assertEquals(before.getString("messages." + oldKey), bundled.getString("messages." + newKey), newKey));
    }

    @Test
    void everyRenamedKeyShipsAndNoOldKeyDoes() {
        RenamedKeys.OLD_BY_NEW.forEach((newKey, oldKey) -> {
            assertNotNull(bundled.getString("messages." + newKey), newKey + " is not in the bundled lang.yml");
            assertFalse(bundled.contains("messages." + oldKey), oldKey + " is still in the bundled lang.yml");
        });
    }

    // the bank section of lang.yml as shipped before the rename
    private static YamlConfiguration previousLang() throws IOException {
        try (InputStream in = RenamedKeysTest.class.getResourceAsStream("/lang-before-bank-rename.yml")) {
            assertNotNull(in);
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                throw new IOException(e);
            }
            return yaml;
        }
    }
}
