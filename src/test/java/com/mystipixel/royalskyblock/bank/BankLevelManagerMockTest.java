package com.mystipixel.royalskyblock.bank;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Mockito has to be able to mock the plugin's own final classes, which are Java 25 bytecode.
class BankLevelManagerMockTest {

    @Test
    void finalJava25ClassCanBeMocked() throws IOException {
        assertEquals(69, classFileMajorVersion(BankLevelManager.class));

        BankLevelManager levels = mock(BankLevelManager.class);
        when(levels.isEmpty()).thenReturn(true);
        when(levels.getNextLevel(1)).thenReturn(Optional.empty());

        assertTrue(levels.isEmpty());
        assertFalse(levels.getNextLevel(1).isPresent());
    }

    private static int classFileMajorVersion(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class");
             DataInputStream data = new DataInputStream(in)) {
            data.readInt();
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}
