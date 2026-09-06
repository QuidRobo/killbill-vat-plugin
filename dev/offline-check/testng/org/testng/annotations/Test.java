/*
 * Copyright 2026 QuidRobo Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.testng.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enough of TestNG's @Test for the offline runner to find test methods.
 *
 * This is a stub. It exists only so the real test sources under src/test/java compile and run
 * without Maven Central. Under a normal build the real TestNG jar shadows it entirely.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Test {

    String[] groups() default {};

    String description() default "";

    boolean enabled() default true;
}
