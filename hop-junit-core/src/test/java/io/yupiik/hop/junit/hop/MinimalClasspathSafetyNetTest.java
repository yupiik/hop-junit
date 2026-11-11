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

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * Guards the deliberate trim of heavy transitive dependencies from {@code hop-junit-core}.
 *
 * <p>{@code hop-junit-core} depends only on {@code hop-core}/{@code hop-engine} and pulls none of the
 * heavy {@code hop-libs}-managed transitive stacks used by the Hop web/REST/SOAP server, the plugin
 * Maven-resolver, the markup renderer, the gRPC remote stack, Avro, the SVG renderer (Batik), the
 * JavaScript engine (Rhino), compression (Snappy), the CLI (picocli) or the WebDAV VFS provider. Those
 * stacks are lazily loaded at runtime and are never touched by the local pipeline engine path. This test
 * boots the Hop environment (the real classloader trigger for the retained core/engine classes) and
 * asserts the excluded stacks are genuinely absent from the test classpath, so an accidental
 * re-introduction is caught here rather than at a consumer's runtime.
 */
class MinimalClasspathSafetyNetTest {
    /**
     * Bootstrapping the Hop environment loads the retained core/engine classes; if a trimming had cut a
     * class the local engine framework still needs, this would throw a linkage error.
     */
    @Test
    void bootsTheHopRuntimeEnvironment() {
        new HopTestEnvironment().init();
    }

    @Test
    void webStackIsNotOnTheClasspath() {
        assertNotResolvable("org.eclipse.jetty.server.Server");
        assertNotResolvable("jakarta.ws.rs.core.Application");
        assertNotResolvable("org.glassfish.jersey.server.ApplicationHandler");
    }

    @Test
    void jakartaServletApiIsRetainedForEngineBootstrap() {
        // HopEnvironment.init() links jakarta.servlet.http.HttpServlet during bootstrap, so the servlet
        // API must stay on the classpath even though the Jetty server implementation is trimmed.
        assertResolvable("jakarta.servlet.http.HttpServlet");
    }

    @Test
    void mavenResolverAndMarkupStackAreNotOnTheClasspath() {
        assertNotResolvable("org.eclipse.aether.RepositorySystem");
        assertNotResolvable("org.commonmark.parser.Parser");
    }

    @Test
    void remoteSerializationAndScriptingStacksAreNotOnTheClasspath() {
        assertNotResolvable("io.grpc.ManagedChannelBuilder");
        assertNotResolvable("org.apache.avro.Schema");
        assertNotResolvable("org.apache.batik.anim.dom.SVGDOMImplementation");
        assertNotResolvable("org.mozilla.javascript.Context");
        assertNotResolvable("org.xerial.snappy.Snappy");
        assertNotResolvable("picocli.CommandLine");
        assertNotResolvable("org.apache.commons.vfs2.provider.webdav.WebdavFileProvider");
        assertNotResolvable("com.nimbusds.jose.JWSObject");
        assertNotResolvable("org.owasp.encoder.Encode");
    }

    @Test
    void jandexIsRetainedForPluginRegistryBootstrap() {
        // PluginRegistry.registerPluginClass resolves org.jboss.jandex.IndexView when HopCoverageRuntime
        // boots, so io.smallrye:jandex must NOT be trimmed.
        assertResolvable("org.jboss.jandex.IndexView");
    }

    @Test
    void httpClient5StackIsRetainedForVfsBootstrap() {
        // HopVfs.createFileSystemManager configures org.apache.hc.core5.ssl.TrustStrategy when the VFS
        // HTTP(S) provider boots, so httpclient5/httpcore5 (the Apache HttpClient 5 stack) must stay.
        assertResolvable("org.apache.hc.client5.http.impl.classic.CloseableHttpClient");
        assertResolvable("org.apache.hc.core5.ssl.TrustStrategy");
        // The legacy Apache HttpClient 4.x httpcore is not needed by the local pipeline engine path.
        assertNotResolvable("org.apache.http.HttpRequest");
    }

    @Test
    void retainedCoreLibrariesRemainPresent() {
        assertResolvable("org.apache.hop.pipeline.PipelineMeta");
        assertResolvable("org.apache.hop.core.HopEnvironment");
        assertResolvable("org.apache.hop.core.vfs.HopVfs");
    }

    private static void assertNotResolvable(final String className) {
        try {
            Class.forName(className, false, MinimalClasspathSafetyNetTest.class.getClassLoader());
            fail(className + " should have been excluded from the classpath");
        } catch (final ClassNotFoundException expected) {
            // expected: the trimmed stack must not be present
        }
    }

    private static void assertResolvable(final String className) {
        try {
            Class.forName(className, false, MinimalClasspathSafetyNetTest.class.getClassLoader());
        } catch (final ClassNotFoundException missing) {
            fail(className + " is expected on the classpath: " + missing);
        }
    }
}
