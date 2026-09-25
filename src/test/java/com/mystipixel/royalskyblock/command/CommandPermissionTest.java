package com.mystipixel.royalskyblock.command;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.willfp.eco.core.Eco;
import com.willfp.eco.core.command.CommandBase;
import com.willfp.eco.core.command.PluginCommandBase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every command in the {@code /is} and {@code /bank} trees has a permission players can actually hold.
 *
 * <p>eco checks a command's permission string as given, and Bukkit treats a permission it has never
 * heard of — the empty string included — as op-only. That is how {@code /is} and {@code /bank} were
 * op-only for every non-op from 2026.36.0 until {@code 0250018}, and ops, who test most things, never
 * see it. So each permission must be non-blank and declared in {@code plugin.yml}, and anything not
 * granted to everyone by default must be one of the deliberately admin-only commands.
 *
 * <p>The real command classes are built against a stubbed eco that just records the tree, so a new
 * subcommand is covered without this test having to list it.
 */
class CommandPermissionTest {

    /** Subcommands meant for staff only; everything else must be open to all players by default. */
    private static final Set<String> ADMIN_ONLY = Set.of("island reload", "island admin");

    private static Eco previousEco;

    @BeforeAll
    static void stubEco() throws Exception {
        // A plain proxy rather than a Mockito mock: Mockito's inline mock maker cannot instrument eco's
        // Eco interface on this JVM.
        Eco eco = proxy(Eco.class, (method, args) -> switch (method.getName()) {
            case "createPluginCommand" -> recording(PluginCommandBase.class, (String) args[2], (String) args[3]);
            case "createSubcommand" -> recording(CommandBase.class, (String) args[2], (String) args[3]);
            default -> throw new UnsupportedOperationException("eco." + method.getName() + " is not stubbed");
        });
        previousEco = setEco(eco);
    }

    @AfterAll
    static void restoreEco() throws Exception {
        setEco(previousEco);
    }

    @Test
    @DisplayName("every /is and /bank command has a declared, reachable permission")
    void everyCommandHasAUsablePermission() {
        // Building the tree only stores the plugin, so an unconstructed instance is enough. Mockito cannot
        // mock it (the eco/Bukkit hierarchy defeats its inline mock maker), and a real one needs a server.
        RoyalSkyblockPlugin plugin = allocate(RoyalSkyblockPlugin.class);
        List<String[]> commands = new ArrayList<>();   // {path, permission}
        walk(new CommandIsland(plugin), "", commands);
        walk(new BankCommand(plugin), "", commands);
        assertTrue(commands.size() > 20, "the /is tree came out suspiciously small: " + commands.size());

        // Permission nodes contain dots, so read plugin.yml with a path separator that never occurs in one.
        YamlConfiguration pluginYml = new YamlConfiguration();
        pluginYml.options().pathSeparator('|');
        try {
            pluginYml.load(new File("src/main/resources/plugin.yml"));
        } catch (Exception e) {
            throw new AssertionError("could not read plugin.yml", e);
        }

        List<String> problems = new ArrayList<>();
        for (String[] command : commands) {
            String path = command[0];
            String permission = command[1];
            if (permission == null || permission.isBlank()) {
                problems.add(path + ": empty permission (op-only for everyone else)");
                continue;
            }
            String def = pluginYml.getString("permissions|" + permission + "|default");
            if (def == null) {
                problems.add(path + ": '" + permission + "' is not declared in plugin.yml with a default");
            } else if (!def.equalsIgnoreCase("true") && !ADMIN_ONLY.contains(path)) {
                problems.add(path + ": '" + permission + "' defaults to " + def + ", so ordinary players lack it");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static void walk(CommandBase command, String parent, List<String[]> out) {
        String path = parent.isEmpty() ? command.getName() : parent + " " + command.getName();
        out.add(new String[]{path, command.getPermission()});
        for (CommandBase sub : command.getSubcommands()) {
            walk(sub, path, out);
        }
    }

    /** A command stub that remembers its name, permission and subcommands, which is all this test reads. */
    private static <T extends CommandBase> T recording(Class<T> type, String name, String permission) {
        List<CommandBase> subs = new ArrayList<>();
        Object[] self = new Object[1];
        T base = proxy(type, (method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getPermission" -> permission;
            case "getSubcommands" -> subs;
            case "addSubcommand" -> {
                subs.add((CommandBase) args[0]);
                yield self[0];
            }
            default -> null;
        });
        self[0] = base;
        return base;
    }

    private interface Handler {
        Object handle(Method method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.handle(method, args)));
    }

    /**
     * eco keeps its singleton in {@code Eco.Instance.eco}; returns what was there before. Written through
     * the field because {@code Instance.set} refuses to replace one that is already set.
     */
    private static Eco setEco(Eco eco) throws Exception {
        java.lang.reflect.Field field = Class.forName("com.willfp.eco.core.Eco$Instance").getDeclaredField("eco");
        field.setAccessible(true);
        Eco previous = (Eco) field.get(null);
        field.set(null, eco);
        return previous;
    }

    /** An instance with no constructor run, via {@code Unsafe.allocateInstance}. */
    private static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not allocate " + type.getName(), e);
        }
    }
}
