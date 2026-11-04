/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
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
package io.yupiik.hop.junit.database.h2.driver;

import static java.util.logging.Logger.getLogger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.function.Function;
import java.util.logging.Logger;
import org.h2.engine.Constants;

/**
 * A JDBC driver that transparently rewrites Oracle SQL into H2 SQL before executing it, so a Hop
 * pipeline written against Oracle ({@code jdbc:oracle:...}) can run unchanged against an in-memory
 * H2 database. Use {@link #wrap(String)} to obtain the driver's {@code jdbc:testoracleextended:h2:...}
 * URL for a plain H2 URL.
 *
 * <p>The driver self-registers in {@link DriverManager} when its class is first loaded.
 *
 * <p>The constructor taking a custom function enables to extend this driver easily.
 */
public final class H2SqlRewriteDriver implements Driver {
    static {
        try {
            DriverManager.registerDriver(new H2SqlRewriteDriver());
        } catch (final SQLException se) {
            throw new ExceptionInInitializerError(se);
        }
    }

    private final Function<String, String> rewriter;
    private final String urlPrefix;
    private final String h2UrlPrefix;

    public H2SqlRewriteDriver(
            final Function<String, String> rewriter, final String urlPrefix, final String h2UrlPrefix) {
        this.rewriter = rewriter;
        this.urlPrefix = urlPrefix;
        this.h2UrlPrefix = h2UrlPrefix;
    }

    public H2SqlRewriteDriver(final Function<String, String> rewriter, final String urlPrefix) {
        this(rewriter, urlPrefix, "jdbc:h2:");
    }

    public H2SqlRewriteDriver() {
        this(new OracleToH2SqlRewriter(), "jdbc:testoracleextended:h2:");
    }

    /**
     * Returns the wrapped {@code jdbc:testoracleextended:h2:...} URL for a plain {@code jdbc:h2:...}
     * URL, or the input unchanged when it is not an H2 URL.
     */
    public String wrap(final String jdbcUrl) {
        if (!jdbcUrl.startsWith(h2UrlPrefix)) {
            throw new IllegalArgumentException("Only H2 JDBC URLs can be wrapped: " + jdbcUrl);
        }
        return urlPrefix + jdbcUrl.substring(h2UrlPrefix.length());
    }

    /** Returns the underlying plain {@code jdbc:h2:...} URL for a wrapped URL (or the input unchanged). */
    public String unwrap(final String jdbcUrl) {
        if (!jdbcUrl.startsWith(urlPrefix)) {
            return jdbcUrl;
        }
        return h2UrlPrefix + jdbcUrl.substring(urlPrefix.length());
    }

    @Override
    public Connection connect(final String url, final Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        final var connection = DriverManager.getConnection(unwrap(url), info);
        return proxy(Connection.class, connection, (proxy, method, args) -> {
            final var name = method.getName();
            if (("prepareStatement".equals(name) || "prepareCall".equals(name) || "nativeSQL".equals(name))
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String sql) {
                args[0] = rewriter.apply(sql);
            }
            final var result = method.invoke(connection, args);
            if (result instanceof PreparedStatement preparedStatement) {
                return proxy(
                        PreparedStatement.class, preparedStatement, (preparedProxy, preparedMethod, preparedArgs) -> {
                            if (preparedArgs != null
                                    && preparedArgs.length > 0
                                    && preparedArgs[0] instanceof String sql) {
                                switch (preparedMethod.getName()) {
                                    case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch" ->
                                        preparedArgs[0] = rewriter.apply(sql);
                                    default -> {
                                        // no-op
                                    }
                                }
                            }
                            return preparedMethod.invoke(preparedStatement, preparedArgs);
                        });
            }
            if (result instanceof Statement statement) {
                return statementProxy(statement);
            }
            return result;
        });
    }

    @Override
    public boolean acceptsURL(final String url) {
        return url != null && url.startsWith(urlPrefix);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info) throws SQLException {
        final var driver = DriverManager.getDriver(unwrap(url));
        return driver.getPropertyInfo(unwrap(url), info);
    }

    @Override
    public int getMajorVersion() {
        return Constants.VERSION_MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return Constants.VERSION_MINOR;
    }

    @Override
    public boolean jdbcCompliant() {
        return true;
    }

    @Override
    public Logger getParentLogger() {
        return getLogger(H2SqlRewriteDriver.class.getName());
    }

    private Statement statementProxy(final Statement statement) {
        return proxy(Statement.class, statement, (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof String sql) {
                switch (method.getName()) {
                    case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch" ->
                        args[0] = rewriter.apply(sql);
                    default -> {
                        // no-op
                    }
                }
            }
            return method.invoke(statement, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final T delegate, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }
            try {
                return handler.invoke(proxy, method, args == null ? null : args.clone());
            } catch (final InvocationTargetException ite) {
                throw ite.getCause();
            }
        });
    }
}
