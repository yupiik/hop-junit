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

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the effective Hop execution parameters for a pipeline or workflow test.
 *
 * <p>The parameters are merged from, in ascending precedence:
 *
 * <ol>
 *   <li>a class-level {@link HopParameters} occurrence (unless the method-level occurrence opts out
 *       via {@link HopParameters#inherit()});
 *   <li>a method-level {@link HopParameters} occurrence;
 *   <li>the per-test parameters declared directly on the pipeline/workflow annotation.
 * </ol>
 *
 * <p>{@link HopParameters} may be used as a meta-annotation on a domain alias (for example
 * {@code @HopSyncParameters}). Alias resolution is deliberately <em>single-level</em>: only the
 * element's directly declared annotations are scanned, and only a directly declared
 * {@link HopParameters} meta-annotation is followed. An alias of an alias is not resolved.
 */
public class HopParametersSupport {
    public HopParametersSupport() {
        // no-op
    }

    /**
     * Computes the merged, unmodifiable parameter map for an annotated test method and its class.
     *
     * @param testClass the test class (read for the class-level batch)
     * @param testMethod the test method (read for the method-level batch)
     * @param explicit parameters declared on the pipeline/workflow annotation; merged last so they
     *     override the class and method level batches
     */
    public Map<String, String> parametersFor(
            final Class<?> testClass, final java.lang.reflect.Method testMethod, final HopParameter[] explicit) {
        final var merged = new LinkedHashMap<String, String>();
        final var methodBatch = findParameters(testMethod);
        final var inherit = methodBatch == null || methodBatch.inherit();
        if (inherit) {
            final var classBatch = findParameters(testClass);
            if (classBatch != null) {
                for (final var parameter : classBatch.value()) {
                    merged.put(parameter.key(), parameter.value());
                }
            }
        }
        if (methodBatch != null) {
            for (final var parameter : methodBatch.value()) {
                merged.put(parameter.key(), parameter.value());
            }
        }
        if (explicit != null) {
            for (final var parameter : explicit) {
                merged.put(parameter.key(), parameter.value());
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    /**
     * Finds the effective {@link HopParameters} on an element, following a single level of alias
     * indirection. Returns {@code null} when the element declares no usable occurrence.
     */
    public HopParameters findParameters(final AnnotatedElement element) {
        final HopParameters direct = element.getAnnotation(HopParameters.class);
        if (direct != null) {
            return direct;
        }
        for (final Annotation annotation : element.getDeclaredAnnotations()) {
            if (annotation.annotationType() == HopParameters.class) {
                continue;
            }
            final HopParameters aliased = annotation.annotationType().getAnnotation(HopParameters.class);
            if (aliased != null) {
                return aliased;
            }
        }
        return null;
    }
}
