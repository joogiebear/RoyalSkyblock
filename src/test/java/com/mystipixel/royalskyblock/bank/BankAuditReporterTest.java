package com.mystipixel.royalskyblock.bank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

// What each island bank movement reports to the audit sink (EconGuard when installed).
class BankAuditReporterTest {

    record Event(UUID uuid, String action, double amount, double balanceAfter, boolean incoming,
                 UUID counterparty, String counterpartyName) {
    }

    private static final UUID STEVE = UUID.randomUUID();
    private static final UUID ALEX = UUID.randomUUID();

    private final List<Event> events = new ArrayList<>();
    private final BankAuditReporter reporter = new BankAuditReporter(Logger.getLogger("BankAuditReporterTest"));
    private final String personal = BankService.personalId(UUID.randomUUID(), STEVE);
    private final String coop = BankService.coopId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        reporter.setSink((uuid, username, action, amount, balanceAfter, incoming, counterparty, counterpartyName) ->
                events.add(new Event(uuid, action, amount, balanceAfter, incoming, counterparty, counterpartyName)));
    }

    @Test
    void ownPersonalDepositAndWithdrawalAreNotIncoming() {
        reporter.deposited(personal, STEVE, "Steve", 100.0, 100.0);
        reporter.withdrew(personal, STEVE, "Steve", 40.0, 100.0);

        assertEquals(List.of(
                new Event(STEVE, "deposited", 100.0, 100.0, false, null, null),
                new Event(STEVE, "withdrew", 40.0, 60.0, false, null, null)), events);
    }

    @Test
    void coopDepositIsNotIncoming() {
        reporter.deposited(coop, ALEX, "Alex", 25.0, 25.0);

        assertEquals(List.of(new Event(ALEX, "coop-deposited", 25.0, 25.0, false, null, null)), events);
    }

    @Test
    void coopWithdrawalIsSplitByDepositor() {
        // 50 was already in the account before the restart, so it has no known depositor
        reporter.deposited(coop, STEVE, "Steve", 100.0, 150.0);
        reporter.deposited(coop, ALEX, "Alex", 30.0, 180.0);
        events.clear();

        reporter.withdrew(coop, STEVE, "Steve", 160.0, 180.0);

        assertEquals(List.of(
                new Event(STEVE, "coop-withdrew", 100.0, 80.0, false, null, null),
                new Event(STEVE, "coop-received", 30.0, 50.0, true, ALEX, "Alex"),
                new Event(STEVE, "coop-withdrew-untracked", 30.0, 20.0, true, null, null)), events);
    }

    @Test
    void anotherMembersDepositIsOnlyCountedOnce() {
        reporter.deposited(coop, ALEX, "Alex", 30.0, 30.0);
        reporter.withdrew(coop, STEVE, "Steve", 20.0, 30.0);
        reporter.withdrew(coop, STEVE, "Steve", 10.0, 10.0);
        events.remove(0);

        assertEquals(List.of(
                new Event(STEVE, "coop-received", 20.0, 10.0, true, ALEX, "Alex"),
                new Event(STEVE, "coop-received", 10.0, 0.0, true, ALEX, "Alex")), events);

        events.clear();
        reporter.withdrew(coop, ALEX, "Alex", 5.0, 5.0);
        assertEquals(List.of(new Event(ALEX, "coop-withdrew-untracked", 5.0, 0.0, true, null, null)), events);
    }

    @Test
    void personalInterestIsIncomingWithoutCounterparty() {
        reporter.interest(personal, STEVE, "Steve", 20.0, 1_020.0);

        assertEquals(List.of(new Event(STEVE, "interest", 20.0, 1_020.0, true, null, null)), events);
    }

    @Test
    void coopInterestIsReportedWhenWithdrawnNotWhenClaimed() {
        reporter.interest(coop, STEVE, "Steve", 20.0, 1_020.0);
        assertEquals(List.of(), events);

        reporter.withdrew(coop, STEVE, "Steve", 20.0, 1_020.0);
        assertEquals(List.of(new Event(STEVE, "coop-withdrew-untracked", 20.0, 1_000.0, true, null, null)), events);
    }

    @Test
    void payoutAfterLeavingACoopIsNotIncoming() {
        reporter.paidOut(STEVE, "Steve", 75.0);

        assertEquals(List.of(new Event(STEVE, "payout", 75.0, 0.0, false, null, null)), events);
    }

    @Test
    void aThrowingSinkIsContained() {
        reporter.setSink((uuid, username, action, amount, balanceAfter, incoming, counterparty, counterpartyName) -> {
            throw new IllegalStateException("ledger down");
        });

        assertDoesNotThrow(() -> reporter.deposited(personal, STEVE, "Steve", 100.0, 100.0));
    }

    @Test
    void noSinkReportsNothing() {
        BankAuditReporter quiet = new BankAuditReporter(Logger.getLogger("BankAuditReporterTest"));
        assertDoesNotThrow(() -> quiet.withdrew(coop, STEVE, "Steve", 10.0, 10.0));
    }
}
