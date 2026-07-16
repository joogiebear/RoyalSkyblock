package com.mystipixel.royalskyblock.bank;

import com.mystipixel.royalbank.api.AccountView;
import com.mystipixel.royalbank.api.BankSnapshot;
import com.mystipixel.royalbank.api.RoyalBankAPI;
import com.mystipixel.royalbank.api.TransactionView;
import com.mystipixel.royalbank.api.UpgradeView;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full coop bank + personal-bank sync backed by RoyalBank's API (fetched from ServicesManager).
 * References {@code com.mystipixel.royalbank.api.*}, so it is only ever constructed once the plugin has
 * confirmed RoyalBank is present (Class.forName guard) — otherwise class verification would fail.
 */
public final class RoyalBankHook implements CoopBank, PersonalBankSync {

    private final RoyalBankAPI api;

    public RoyalBankHook() {
        RegisteredServiceProvider<RoyalBankAPI> rsp =
                Bukkit.getServicesManager().getRegistration(RoyalBankAPI.class);
        this.api = rsp != null ? rsp.getProvider() : null;
    }

    public boolean ready() {
        return api != null;
    }

    // ── CoopBank ─────────────────────────────────────────────────────────────────

    @Override
    public boolean available() {
        return api != null;
    }

    @Override
    public String backendName() {
        return "RoyalBank";
    }

    @Override
    public boolean supportsUpgrades() {
        return true;
    }

    @Override
    public CoopAccountView view(UUID coopId, String label) {
        AccountView v = api.getAccountView(coopId, label);
        return new CoopAccountView(v.balance(), v.level(), v.levelName(), v.maxBalance());
    }

    @Override
    public String deposit(Player from, UUID coopId, String label, double amount) {
        return api.accountDeposit(from, coopId, label, amount);
    }

    @Override
    public String withdraw(Player to, UUID coopId, String label, double amount) {
        return api.accountWithdraw(to, coopId, label, amount);
    }

    @Override
    public CoopUpgrade upgradeInfo(UUID coopId, String label) {
        UpgradeView u = api.getUpgradeView(coopId, label);
        return new CoopUpgrade(u.maxed(), u.nextLevel(), u.nextName(), u.moneyCost(), u.itemsText(), u.nextMaxBalance());
    }

    @Override
    public String upgrade(Player by, UUID coopId, String label) {
        return api.accountUpgrade(by, coopId, label);
    }

    @Override
    public List<CoopTxn> transactions(UUID coopId, int limit) {
        List<CoopTxn> out = new ArrayList<>();
        for (TransactionView t : api.getAccountTransactions(coopId, limit)) {
            out.add(new CoopTxn(t.type(), t.amount(), t.balanceAfter(), t.timestamp(), t.note()));
        }
        return out;
    }

    // ── PersonalBankSync ─────────────────────────────────────────────────────────

    @Override
    public boolean active() {
        return api != null;
    }

    @Override
    public BankSnapshotState export(UUID playerId) {
        BankSnapshot s = api.exportAccount(playerId);
        return new BankSnapshotState(s.balance(), s.level(), s.lastInterestClaim(), s.bonusClaimed());
    }

    @Override
    public void restore(UUID playerId, BankSnapshotState state) {
        api.importAccount(playerId, new BankSnapshot(state.balance(), state.level(), state.lastInterest(), state.bonus()));
    }

    @Override
    public void reset(UUID playerId) {
        api.resetAccount(playerId);
    }
}
