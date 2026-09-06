/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Runs the repository's real TestNG tests without TestNG, Maven or a network.
 *
 * It runs the same classes under {@code src/test/java} that {@code mvn test} runs, so the offline
 * check and the real build cannot drift apart. What it does not do is anything TestNG does beyond
 * "call every public no-argument @Test method and report what threw": no groups, no ordering, no
 * data providers, no expected exceptions. Those are not used here, and if they ever are, this
 * runner should be deleted rather than grown.
 */
public final class TinyTestRunner {

    public static void main(final String[] args) throws Exception {
        final List<String> classNames = new ArrayList<String>(Arrays.asList(args));
        if (classNames.isEmpty()) {
            System.err.println("usage: TinyTestRunner <fully.qualified.TestClass> ...");
            System.exit(2);
        }

        int passed = 0;
        final List<String> failures = new ArrayList<String>();

        for (final String className : classNames) {
            final Class<?> testClass = Class.forName(className);
            System.out.println();
            System.out.println("== " + testClass.getSimpleName() + " ==");

            final Object instance = testClass.getDeclaredConstructor().newInstance();
            for (final Method method : testClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Test.class)
                    || method.getParameterCount() != 0
                    || !method.getAnnotation(Test.class).enabled()) {
                    continue;
                }
                try {
                    method.invoke(instance);
                    passed++;
                    System.out.println("  ok   " + method.getName());
                } catch (final InvocationTargetException e) {
                    final Throwable cause = e.getCause() == null ? e : e.getCause();
                    failures.add(testClass.getSimpleName() + "." + method.getName() + ": " + cause);
                    System.out.println("  FAIL " + method.getName());
                    System.out.println("       " + cause);
                }
            }
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("ALL " + passed + " TESTS PASSED");
            return;
        }
        System.out.println(passed + " passed, " + failures.size() + " FAILED");
        for (final String failure : failures) {
            System.out.println("  " + failure);
        }
        System.exit(1);
    }
}
