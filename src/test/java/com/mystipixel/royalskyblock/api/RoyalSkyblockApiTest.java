package com.mystipixel.royalskyblock.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Other plugins look this method up by name and signature, so changing either breaks them silently.
class RoyalSkyblockApiTest {

    @Test
    void savePlayerNowKeepsItsReflectionSignature() throws Exception {
        Method method = Class.forName("com.mystipixel.royalskyblock.api.RoyalSkyblockApi")
                .getMethod("savePlayerNow", UUID.class);

        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(boolean.class, method.getReturnType());
    }
}
