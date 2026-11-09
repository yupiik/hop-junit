/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.junit.hop;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares a single Hop execution parameter (a name/value pair).
 *
 * <p>A parameter is bundled into the {@link HopParameter[] parameters()} of {@link HopPipeline} /
 * {@link HopWorkflow}, or grouped in a batch via {@link HopParameters}. The effective value of a key is
 * resolved with last-one-wins semantics (see {@link HopParametersSupport}) and is set as a Hop
 * variable before the artifact runs and substituted into the artifact XML.
 *
 * @see HopParameters
 * @see HopParametersSupport
 */
@Target({ANNOTATION_TYPE, TYPE, FIELD, METHOD, PARAMETER})
@Retention(RUNTIME)
public @interface HopParameter {
    /** The parameter name (also the Hop variable name and the {@code ${name}} substitution key). */
    String key();

    /** The parameter value. */
    String value();
}
