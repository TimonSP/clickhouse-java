package com.clickhouse.jdbc;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.WeakHashMap;

import com.clickhouse.client.ClickHouseClient;
import com.clickhouse.client.ClickHouseVersion;
import com.clickhouse.client.config.ClickHouseClientOption;
import com.clickhouse.client.config.ClickHouseOption;
import com.clickhouse.client.logging.Logger;
import com.clickhouse.client.logging.LoggerFactory;
import com.clickhouse.jdbc.internal.ClickHouseConnectionImpl;
import com.clickhouse.jdbc.internal.ClickHouseJdbcUrlParser;

/**
 * JDBC driver for ClickHouse. It takes a connection string like below for
 * connecting to ClickHouse server:
 * {@code jdbc:clickhouse://[<user>:<password>@]<server>[:<port>][/<db>][?parameter1=value1&parameter2=value2]}
 *
 * <p>
 * For examples:
 * <ul>
 * <li>{@code jdbc:clickhouse://localhost:8123/system}</li>
 * <li>{@code jdbc:clickhouse://admin:password@localhost/system?socket_time=30}</li>
 * <li>{@code jdbc:clickhouse://localhost/system?protocol=grpc}</li>
 * </ul>
 */
public class ClickHouseDriver implements Driver {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseDriver.class);

    static final String driverVersionString;
    static final ClickHouseVersion driverVersion;
    static final ClickHouseVersion specVersion;

    static final java.util.logging.Logger parentLogger = java.util.logging.Logger.getLogger("com.clickhouse.jdbc");

    static {
        driverVersionString = ClickHouseDriver.class.getPackage().getImplementationVersion();
        driverVersion = ClickHouseVersion.of(driverVersionString);
        specVersion = ClickHouseVersion.of(ClickHouseDriver.class.getPackage().getSpecificationVersion());

        try {
            DriverManager.registerDriver(new ClickHouseDriver());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        log.debug("ClickHouse Driver %s(JDBC: %s) registered", driverVersion, specVersion);
    }

    private DriverPropertyInfo create(ClickHouseOption option, Properties props) {
        DriverPropertyInfo propInfo = new DriverPropertyInfo(option.getKey(),
                props.getProperty(option.getKey(), String.valueOf(option.getEffectiveDefaultValue())));
        propInfo.required = false;
        propInfo.description = option.getDescription();
        propInfo.choices = null;

        Class<?> clazz = option.getValueType();
        if (Boolean.class == clazz || boolean.class == clazz) {
            propInfo.choices = new String[] { "true", "false" };
        } else if (clazz.isEnum()) {
            Object[] values = clazz.getEnumConstants();
            String[] names = new String[values.length];
            int index = 0;
            for (Object v : values) {
                names[index++] = ((Enum<?>) v).name();
            }
            propInfo.choices = names;
        }
        return propInfo;
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && (url.startsWith(ClickHouseJdbcUrlParser.JDBC_CLICKHOUSE_PREFIX)
                || url.startsWith(ClickHouseJdbcUrlParser.JDBC_ABBREVIATION_PREFIX));
    }

    @Override
    public ClickHouseConnection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        log.debug("Creating connection");
        return new ClickHouseConnectionImpl(url, info);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        try {
            info = ClickHouseJdbcUrlParser.parse(url, info).getProperties();
        } catch (Exception e) {
            log.error("Could not parse url %s", url, e);
        }

        List<DriverPropertyInfo> result = new ArrayList<>(ClickHouseClientOption.values().length * 2);
        for (ClickHouseClientOption option : ClickHouseClientOption.values()) {
            result.add(create(option, info));
        }

        // client-specific configuration
        try {
            for (ClickHouseClient c : ServiceLoader.load(ClickHouseClient.class, getClass().getClassLoader())) {
                Class<? extends ClickHouseOption> clazz = c.getOptionClass();
                if (clazz == null || clazz == ClickHouseClientOption.class) {
                    continue;
                }
                for (ClickHouseOption option : clazz.getEnumConstants()) {
                    result.add(create(option, info));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load client-specific configuration", e);
        }

        DriverPropertyInfo custom = new DriverPropertyInfo(ClickHouseJdbcUrlParser.PROP_JDBC_COMPLIANT, "true");
        custom.choices = new String[] { "true", "false" };
        custom.description = "Whether to enable JDBC-compliant features like fake transaction and standard UPDATE and DELETE statements.";
        result.add(custom);
        return result.toArray(new DriverPropertyInfo[0]);
    }

    @Override
    public int getMajorVersion() {
        return driverVersion.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return driverVersion.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return parentLogger;
    }
}