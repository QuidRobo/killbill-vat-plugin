/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.testng;

/**
 * The subset of TestNG's Assert the tests in this repository use.
 *
 * This is a stub for the offline runner, matching TestNG's argument order: actual first, then
 * expected, then the message. Under a normal Maven build the real TestNG jar shadows it.
 */
public final class Assert {

    private Assert() {
    }

    public static void assertEquals(final Object actual, final Object expected) {
        assertEquals(actual, expected, null);
    }

    public static void assertEquals(final Object actual, final Object expected, final String message) {
        if (actual == null ? expected == null : actual.equals(expected)) {
            return;
        }
        throw new AssertionError(prefix(message) + "expected <" + expected + "> but was <" + actual + ">");
    }

    public static void assertEquals(final String actual, final String expected, final String message) {
        assertEquals((Object) actual, (Object) expected, message);
    }

    public static void assertEquals(final int actual, final int expected) {
        assertEquals(actual, expected, null);
    }

    public static void assertEquals(final int actual, final int expected, final String message) {
        if (actual != expected) {
            throw new AssertionError(prefix(message) + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertEquals(final boolean actual, final boolean expected) {
        assertEquals(actual, expected, null);
    }

    public static void assertEquals(final boolean actual, final boolean expected, final String message) {
        if (actual != expected) {
            throw new AssertionError(prefix(message) + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void assertTrue(final boolean condition) {
        assertTrue(condition, null);
    }

    public static void assertTrue(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(prefix(message) + "expected true");
        }
    }

    public static void assertFalse(final boolean condition) {
        assertFalse(condition, null);
    }

    public static void assertFalse(final boolean condition, final String message) {
        if (condition) {
            throw new AssertionError(prefix(message) + "expected false");
        }
    }

    public static void assertNull(final Object actual) {
        assertNull(actual, null);
    }

    public static void assertNull(final Object actual, final String message) {
        if (actual != null) {
            throw new AssertionError(prefix(message) + "expected null but was <" + actual + ">");
        }
    }

    public static void assertNotNull(final Object actual) {
        assertNotNull(actual, null);
    }

    public static void assertNotNull(final Object actual, final String message) {
        if (actual == null) {
            throw new AssertionError(prefix(message) + "expected a value but was null");
        }
    }

    public static void fail(final String message) {
        throw new AssertionError(message);
    }

    private static String prefix(final String message) {
        return message == null || message.isEmpty() ? "" : message + " :: ";
    }
}
