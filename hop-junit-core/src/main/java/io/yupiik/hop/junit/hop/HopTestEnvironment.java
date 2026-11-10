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

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hop.core.HopEnvironment;

/**
 * Bootstraps the Apache Hop runtime environment ({@link HopEnvironment#init()}) exactly once per
 * JVM. Create an instance and call {@link #init()} from the extension constructors; the underlying
 * init is idempotent thanks to an internal guard.
 */
public class HopTestEnvironment {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    public HopTestEnvironment() {
        // no-op
    }

    public void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        try {
            HopEnvironment.init();
        } catch (final Exception error) {
            throw new IllegalStateException("Unable to initialize the Apache Hop environment", error);
        }
    }
}
