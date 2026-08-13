package com.mystipixel.royalskyblock.data;

import com.mystipixel.royalskyblock.bank.BankTxn;
import com.mystipixel.royalskyblock.island.IslandRole;
import com.mystipixel.royalskyblock.profile.ProfileMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of {@link EcoStorage} that turn a row into a string and back.
 *
 * <p>A store with no schema cannot reject a value it can't parse — a roster entry that loses a field
 * comes back as a member silently dropped from an island, and a ledger line that splits on the wrong
 * character comes back as a balance nobody can explain. These are the failures that never throw, so
 * they get tested directly rather than being left to a live server to reveal.
 *
 * <p>The eco-facing half needs a running server ({@code PlayerProfile.load} goes through
 * {@code Eco.get()}) and is verified against one instead.
 */
class EcoStorageRowsTest {

    @Test
    @DisplayName("a roster entry survives the round trip")
    void memberRoundTrip() {
        ProfileMember member = new ProfileMember(UUID.randomUUID(), "joogiebear", IslandRole.CO_OWNER,
                1_755_000_000_000L);

        ProfileMember read = EcoStorage.readMember(EcoStorage.writeMember(member));

        assertEquals(member.uuid(), read.uuid());
        assertEquals(member.name(), read.name());
        assertEquals(member.role(), read.role());
        assertEquals(member.joinedAt(), read.joinedAt(),
                "joinedAt is epoch millis — it must not come back through an int");
    }

    @Test
    @DisplayName("a member with no name keeps its other fields")
    void memberWithoutName() {
        // The SQL column defaulted to '' and rosters written before names were tracked still have it.
        ProfileMember member = new ProfileMember(UUID.randomUUID(), null, IslandRole.MEMBER, 42L);

        ProfileMember read = EcoStorage.readMember(EcoStorage.writeMember(member));

        assertEquals("", read.name());
        assertEquals(member.uuid(), read.uuid());
        assertEquals(42L, read.joinedAt());
    }

    @Test
    @DisplayName("an unknown role falls back to MEMBER rather than dropping the member")
    void unknownRoleFallsBack() {
        UUID id = UUID.randomUUID();

        ProfileMember read = EcoStorage.readMember(id + ";someone;ARCHITECT;7");

        assertEquals(IslandRole.MEMBER, read.role(), "a role removed in a later version must not "
                + "cost the island a member");
        assertEquals(id, read.uuid());
    }

    @Test
    @DisplayName("a truncated roster entry is rejected, not half-read")
    void malformedMemberIsNull() {
        assertNull(EcoStorage.readMember(UUID.randomUUID() + ";someone;MEMBER"));
        assertNull(EcoStorage.readMember(""));
    }

    @Test
    @DisplayName("a ledger entry survives the round trip")
    void txnRoundTrip() {
        BankTxn txn = new BankTxn("DEPOSIT", 1234.56, 9876.54, 1_755_000_000L, "interest");

        BankTxn read = EcoStorage.readTxn(EcoStorage.writeTxn(txn));

        assertEquals(txn.type(), read.type());
        assertEquals(txn.amount(), read.amount());
        assertEquals(txn.balanceAfter(), read.balanceAfter());
        assertEquals(txn.timestamp(), read.timestamp());
        assertEquals(txn.note(), read.note());
    }

    @Test
    @DisplayName("a note containing the separator does not corrupt the entry")
    void txnNoteWithSeparator() {
        // Notes are player-facing text, so nothing stops one containing the delimiter. This is the
        // reason the note is encoded rather than written straight into the line.
        BankTxn txn = new BankTxn("WITHDRAW", 10.0, 0.0, 1L, "paid; then; left");

        BankTxn read = EcoStorage.readTxn(EcoStorage.writeTxn(txn));

        assertEquals("paid; then; left", read.note());
        assertEquals(10.0, read.amount());
    }

    @Test
    @DisplayName("an empty note round-trips as empty, not null")
    void txnEmptyNote() {
        BankTxn read = EcoStorage.readTxn(EcoStorage.writeTxn(new BankTxn("INTEREST", 1.0, 2.0, 3L, "")));

        assertEquals("", read.note());
    }

    @Test
    @DisplayName("a truncated ledger entry is rejected")
    void malformedTxnIsNull() {
        assertNull(EcoStorage.readTxn("DEPOSIT;1.0;2.0"));
    }

    @Test
    @DisplayName("derived ids are stable and differ per prefix")
    void derivedIsStableAndScoped() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();

        assertEquals(EcoStorage.profileDataUuid(profile, player), EcoStorage.profileDataUuid(profile, player),
                "the same row must resolve to the same id on every node and every restart");
        assertNotEquals(EcoStorage.profileDataUuid(profile, player), EcoStorage.profileDataUuid(player, profile),
                "the pair is ordered — swapping it must not collide");
        assertNotEquals(EcoStorage.derived("rsb-bank", profile.toString()),
                EcoStorage.derived("rsb-player", profile.toString()),
                "prefixes keep the id spaces apart");
    }

    @Test
    @DisplayName("derived ids are version 3, so they cannot collide with random ids")
    void derivedCannotCollideWithRandomIds() {
        // Islands, profiles and online-mode players all use random (version 4) UUIDs. Name-based ones
        // are version 3, and the version nibble is part of the value, so the two spaces are disjoint
        // by construction rather than by luck.
        assertEquals(3, EcoStorage.bankUuid("c:" + UUID.randomUUID()).version());
        assertEquals(4, UUID.randomUUID().version());
    }

    @Test
    @DisplayName("a bank account id round-trips whatever characters it contains")
    void bankIdsAreOpaque() {
        // Account ids are p:<profile>:<player> and c:<profile> — colons and all.
        UUID personal = EcoStorage.bankUuid("p:" + UUID.randomUUID() + ":" + UUID.randomUUID());
        UUID coop = EcoStorage.bankUuid("c:" + UUID.randomUUID());

        assertNotEquals(personal, coop);
        assertTrue(personal.version() == 3 && coop.version() == 3);
    }
}
