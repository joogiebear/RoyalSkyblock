package com.mystipixel.royalskyblock.hooks;

import com.mystipixel.royalskyblock.bank.BankAuditSink;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

// Calls EconGuard.record(...) by reflection so there is no build-time dependency. Only resolved when
// EconGuard is enabled; returns null otherwise so the bank keeps its no-op sink.
public final class EconGuardHook implements BankAuditSink {

    private static final String SOURCE_BANK = "bank";

    private final Method bridge;
    private final Logger logger;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    private EconGuardHook(Method bridge, Logger logger) {
        this.bridge = bridge;
        this.logger = logger;
    }

    public static @Nullable EconGuardHook resolve(Logger logger) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            return null;
        }
        try {
            Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
            Method record = econGuard.getMethod("record",
                    UUID.class, String.class, String.class, String.class,
                    double.class, boolean.class, double.class,
                    UUID.class, String.class, String.class, String.class);
            return new EconGuardHook(record, logger);
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("EconGuard is installed but has no record bridge this version can call; bank activity is not reported to it.");
            return null;
        }
    }

    @Override
    public void record(UUID uuid, String username, String action, double amount, double balanceAfter,
                       boolean incoming, @Nullable UUID counterparty, @Nullable String counterpartyName) {
        try {
            bridge.invoke(null, uuid, username, SOURCE_BANK, action, amount, incoming, balanceAfter,
                    counterparty, counterpartyName, null, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // runs after the money has moved, so it must not reach the bank action; logged once so a
            // broken bridge doesn't go unnoticed or flood the console
            if (failureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "Could not report bank activity to EconGuard; further failures are not logged", e);
            }
        }
    }
}
