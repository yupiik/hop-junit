/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.junit.hop;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HopParametersSupportTest {
    /** Domain alias: a single-level meta-annotation on {@link HopParameters}. */
    @Target({TYPE, METHOD})
    @Retention(RUNTIME)
    @HopParameters({
        @HopParameter(key = "sync.table", value = "ORDERS"),
        @HopParameter(key = "sync.partition", value = "P20260101"),
    })
    @interface HopSyncParameters {}

    /** An alias of an alias - must NOT be resolved (single-level only). */
    @Target({TYPE, METHOD})
    @Retention(RUNTIME)
    @HopSyncParameters
    @interface HopSyncParametersNested {}

    @HopParameters({
        @HopParameter(key = "class", value = "1"),
    })
    static class ClassLevelAnnotated {
        void plain() {}
    }

    @HopParameters({
        @HopParameter(key = "class", value = "1"),
    })
    static class ClassAndMethodAnnotated {
        @HopParameters({
            @HopParameter(key = "method", value = "2"),
            @HopParameter(key = "shared", value = "method"),
        })
        void method() {}
    }

    @HopParameters({
        @HopParameter(key = "class", value = "1"),
    })
    static class InheritFalse {
        @HopParameters(value = @HopParameter(key = "method", value = "2"), inherit = false)
        void method() {}
    }

    static class MethodAlias {
        @HopSyncParameters
        void method() {}
    }

    @HopSyncParameters
    static class ClassAlias {
        void plain() {}
    }

    static class NestedAlias {
        @HopSyncParametersNested
        void method() {}
    }

    static class NoParams {
        void plain() {}
    }

    @Target(METHOD)
    @Retention(RUNTIME)
    @interface WithExplicit {
        HopParameter[] parameters() default {};
    }

    static class ExplicitParams {
        @HopParameters({
            @HopParameter(key = "method", value = "2"),
        })
        void method() {}

        @WithExplicit(
                parameters = {
                    @HopParameter(key = "explicit", value = "3"),
                    @HopParameter(key = "method", value = "override"),
                })
        void withExplicit() {}
    }

    private static Map<String, String> paramsFor(final Class<?> type, final String methodName) throws Exception {
        return new HopParametersSupport().parametersFor(type, methodOf(type, methodName), null);
    }

    private static Method methodOf(final Class<?> type, final String name) throws NoSuchMethodException {
        for (final Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Test
    void findReturnsClassLevelBatch() throws Exception {
        final var batch = new HopParametersSupport().findParameters(ClassLevelAnnotated.class);
        assertEquals(1, batch.value().length);
    }

    @Test
    void emptyWhenNoAnnotations() throws Exception {
        assertTrue(paramsFor(NoParams.class, "plain").isEmpty());
    }

    @Test
    void classLevelBatchAppliesWhenMethodHasNone() throws Exception {
        assertEquals(Map.of("class", "1"), paramsFor(ClassLevelAnnotated.class, "plain"));
    }

    @Test
    void mergesClassAndMethodWithMethodWinningSharedKeys() throws Exception {
        assertEquals(
                Map.of("class", "1", "method", "2", "shared", "method"),
                paramsFor(ClassAndMethodAnnotated.class, "method"));
    }

    @Test
    void inheritFalseDropsClassLevelBatch() throws Exception {
        assertEquals(Map.of("method", "2"), paramsFor(InheritFalse.class, "method"));
    }

    @Test
    void resolvesSingleLevelAliasOnMethod() throws Exception {
        assertEquals(
                Map.of("sync.table", "ORDERS", "sync.partition", "P20260101"), paramsFor(MethodAlias.class, "method"));
    }

    @Test
    void classLevelAliasAppliesToMethodsWithoutOwnBatch() throws Exception {
        assertEquals(
                Map.of("sync.table", "ORDERS", "sync.partition", "P20260101"), paramsFor(ClassAlias.class, "plain"));
    }

    @Test
    void aliasResolutionIsSingleLevelNotRecursive() throws Exception {
        assertNull(new HopParametersSupport().findParameters(NestedAlias.class.getDeclaredMethod("method")));
    }

    @Test
    void explicitParametersAreMergedLast() throws Exception {
        final var method = methodOf(ExplicitParams.class, "withExplicit");
        final var explicit = method.getAnnotation(WithExplicit.class).parameters();
        final var params = new HopParametersSupport().parametersFor(ExplicitParams.class, method, explicit);
        assertEquals(Map.of("method", "override", "explicit", "3"), params);
    }
}
