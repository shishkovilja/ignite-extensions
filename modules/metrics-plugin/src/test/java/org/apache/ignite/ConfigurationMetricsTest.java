/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.StreamSupport;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.metric.GridMetricManager;
import org.apache.ignite.internal.processors.metric.MetricRegistryImpl;
import org.apache.ignite.lifecycle.LifecycleBean;
import org.apache.ignite.lifecycle.LifecycleEventType;
import org.apache.ignite.plugin.security.SecurityCredentials;
import org.apache.ignite.spi.metric.Metric;
import org.apache.ignite.spi.metric.ObjectMetric;
import org.apache.ignite.spi.metric.ReadOnlyMetricRegistry;
import org.apache.ignite.spi.systemview.view.ConfigurationView;
import org.apache.ignite.testframework.ListeningTestLogger;
import org.apache.ignite.testframework.LogListener;
import org.apache.ignite.testframework.junits.WithSystemProperty;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.ConfigurationMetricsPluginProvider.CONFIGURATION_METRICS;
import static org.apache.ignite.IgniteCommonsSystemProperties.IGNITE_TO_STRING_INCLUDE_SENSITIVE;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_CONFIGURATION_VIEW_PACKAGES;
import static org.apache.ignite.internal.IgniteKernal.CFG_VIEW;
import static org.apache.ignite.internal.processors.metric.impl.MetricUtils.metricName;

/** Tests for {@link ConfigurationMetricsPluginProvider}. */
public class ConfigurationMetricsTest extends GridCommonAbstractTest {
    /** Value of the sensitive properties. */
    private static final String SECRET = "top-secret";

    /** Credentials: a configuration object in a sensitive property. */
    public static final SecurityCredentials SECURITY_CREDENTIALS = new SecurityCredentials("login", SECRET);

    /** Maximum size of the default data region. */
    private static final long MAX_SIZE = 128L * 1024 * 1024;

    /** Registry of the default data region. */
    private static final String DEFAULT_REGION = metricName(CONFIGURATION_METRICS, "DataStorageConfiguration",
        "DefaultDataRegionConfiguration");

    /** Registry of the test bean. */
    private static final String BEAN = metricName(CONFIGURATION_METRICS, "LifecycleBeans_0");

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        return super.getConfiguration(igniteInstanceName)
            .setUserAttributes(Collections.singletonMap("role", "test"))
            .setDataStorageConfiguration(new DataStorageConfiguration()
                .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                    .setMaxSize(MAX_SIZE)
                    .setMetricsEnabled(true)))
            .setLifecycleBeans(new TestBean());
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        startGrid(0);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /** Tests that the metrics match the configuration system view. */
    @Test
    public void testWholeConfigurationRegistry() {
        Map<String, String> expected = new TreeMap<>();

        for (ConfigurationView row : grid(0).context().systemView().<ConfigurationView>view(CFG_VIEW)) {
            // "Property[i]" -> "Property_i".
            String name = row.name().replaceAll("\\[(\\d+)]", "_$1");

            expected.put(metricName(CONFIGURATION_METRICS, name), row.value());
        }

        Map<String, String> metrics = metrics(grid(0));

        assertFalse(expected.isEmpty());
        assertEquals(expected, metrics);
    }

    /** Tests that a nested configuration object is a registry with its properties as metrics. */
    @Test
    public void testSubRegistry() {
        GridMetricManager mmgr = grid(0).context().metric();

        MetricRegistryImpl dfltRegionReg = mmgr.registry(DEFAULT_REGION);

        assertNotNull(dfltRegionReg);

        assertTrue(StreamSupport.stream(dfltRegionReg.spliterator(), false)
            .anyMatch(m -> metricName(DEFAULT_REGION, "InitialSize").equals(m.name())));
    }

    /** Tests that values of any type are strings, as in the system view. */
    @Test
    public void testFindMetrics() {
        GridMetricManager mmgr = grid(0).context().metric();

        assertEquals(getTestIgniteInstanceName(0),
            mmgr.find(metricName(CONFIGURATION_METRICS, "IgniteInstanceName"), ObjectMetric.class).value());

        assertEquals(String.valueOf(grid(0).configuration().getPublicThreadPoolSize()),
            mmgr.find(metricName(CONFIGURATION_METRICS, "PublicThreadPoolSize"), ObjectMetric.class).value());

        assertEquals("true", mmgr.find(metricName(DEFAULT_REGION, "MetricsEnabled"), ObjectMetric.class).value());

        assertEquals(String.valueOf(new DataRegionConfiguration().getEvictionThreshold()),
            mmgr.find(metricName(DEFAULT_REGION, "EvictionThreshold"), ObjectMetric.class).value());

        assertEquals(String.valueOf(MAX_SIZE),
            mmgr.find(metricName(DEFAULT_REGION, "MaxSize"), ObjectMetric.class).value());

        assertEquals("7", mmgr.find(metricName(BEAN, "ShortValue"), ObjectMetric.class).value());
        assertEquals("0.1", mmgr.find(metricName(BEAN, "FloatValue"), ObjectMetric.class).value());
    }

    /** Tests that sensitive properties are not registered if sensitive data is excluded. */
    @Test
    @WithSystemProperty(key = IGNITE_TO_STRING_INCLUDE_SENSITIVE, value = "false")
    public void testSensitiveValuesSkipped() {
        Map<String, String> metrics = metrics(grid(0));

        // Not registered, even if not set.
        assertFalse(metrics.containsKey(metricName(BEAN, "KeyStorePassword")));
        assertFalse(metrics.containsKey(metricName(BEAN, "Token")));
        assertFalse(metrics.containsKey(metricName(BEAN, "Credentials", "Password")));

        // "User" is not at the end of the name.
        assertEquals("{role=test}", metrics.get(metricName(CONFIGURATION_METRICS, "UserAttributes")));

        // No metric exposes the secret.
        for (Map.Entry<String, String> m : metrics.entrySet())
            assertFalse(m.getKey(), String.valueOf(m.getValue()).contains(SECRET));
    }

    /** Tests that sensitive values are exposed by default, as in the system view. */
    @Test
    public void testSensitiveDataExposedByDefault() {
        Map<String, String> metrics = metrics(grid(0));

        assertEquals(SECRET, metrics.get(metricName(BEAN, "KeyStorePassword")));

        // Not set: registered with the null value.
        assertTrue(metrics.containsKey(metricName(BEAN, "Token")));
        assertNull(metrics.get(metricName(BEAN, "Token")));

        // Nested object of a sensitive property is a registry.
        assertEquals("login", metrics.get(metricName(BEAN, "Credentials", "Login")));
        assertEquals(SECRET, metrics.get(metricName(BEAN, "Credentials", "Password")));
    }

    /** Tests that a getter failure is logged, and the node starts without the metrics. */
    @Test
    public void testRegistrationFailure() throws Exception {
        LogListener lsnr = LogListener.matches("Failed to register the configuration metrics").build();

        IgniteConfiguration cfg = getConfiguration(getTestIgniteInstanceName(1))
            .setGridLogger(new ListeningTestLogger(log, lsnr))
            .setLifecycleBeans(new FailingBean());

        try (IgniteEx ignite = startGrid(cfg)) {
            assertTrue(lsnr.check());
            assertTrue(metrics(ignite).isEmpty());
        }
    }

    /** Tests that an {@link Error} is logged, and the node starts without the metrics. */
    @Test
    @WithSystemProperty(key = IGNITE_CONFIGURATION_VIEW_PACKAGES, value = "com.sbt.ignite")
    public void testErrorDoesNotFailNodeStart() throws Exception {
        LogListener lsnr = LogListener.matches("Failed to register the configuration metrics").build();

        IgniteConfiguration cfg = getConfiguration(getTestIgniteInstanceName(1))
            .setGridLogger(new ListeningTestLogger(log, lsnr))
            .setLifecycleBeans(new EndlessBean());

        // EndlessBean in SystemView will cause StackOverflow.
        try (IgniteEx ignite = startGrid(cfg)) {
            assertTrue(lsnr.check());
            assertTrue(metrics(ignite).isEmpty());
        }
    }

    /** Tests that the metrics are removed on the node stop. */
    @Test
    public void testMetricsRemovedOnStop() throws Exception {
        IgniteEx ignite = startGrid(1);

        assertFalse(metrics(ignite).isEmpty());

        stopGrid(1);

        assertTrue(metrics(ignite).isEmpty());
    }

    /**
     * @param ignite Ignite.
     * @return Configuration metrics, keyed by the full metric name.
     */
    private static Map<String, String> metrics(IgniteEx ignite) {
        Map<String, String> res = new TreeMap<>();

        for (ReadOnlyMetricRegistry mreg : ignite.context().metric()) {
            if (mreg.name().equals(CONFIGURATION_METRICS) || mreg.name().startsWith(CONFIGURATION_METRICS + '.')) {
                for (Metric m : mreg)
                    res.put(m.name(), m.getAsString());
            }
        }

        return res;
    }

    /** Configuration bean: a registry, since its class is in the {@code org.apache.ignite} package. */
    public static class TestBean implements LifecycleBean {
        /** */
        private short shortVal = 7;

        /** */
        private float floatVal = 0.1f;

        /** */
        private String keyStorePwd = SECRET;

        /** */
        private String token;

        /** {@inheritDoc} */
        @Override public void onLifecycleEvent(LifecycleEventType evt) {
            // No-op.
        }

        /** */
        public short getShortValue() {
            return shortVal;
        }

        /** */
        public void setShortValue(short shortVal) {
            this.shortVal = shortVal;
        }

        /** */
        public float getFloatValue() {
            return floatVal;
        }

        /** */
        public void setFloatValue(float floatVal) {
            this.floatVal = floatVal;
        }

        /** */
        public String getKeyStorePassword() {
            return keyStorePwd;
        }

        /** */
        public void setKeyStorePassword(String keyStorePwd) {
            this.keyStorePwd = keyStorePwd;
        }

        /** */
        public String getToken() {
            return token;
        }

        /** */
        public void setToken(String token) {
            this.token = token;
        }

        /** */
        public SecurityCredentials getCredentials() {
            return SECURITY_CREDENTIALS;
        }

        /** */
        public void setCredentials(SecurityCredentials ignore) {
            // No-op.
        }
    }

    /** Configuration bean with a property that can't be read. */
    public static class FailingBean implements LifecycleBean {
        /** {@inheritDoc} */
        @Override public void onLifecycleEvent(LifecycleEventType evt) {
            // No-op.
        }

        /** */
        public String getValue() {
            throw new IllegalStateException("Test failure.");
        }

        /** */
        public void setValue(String val) {
            // No-op.
        }
    }

    /** Configuration bean returning a new nested bean on each call: reading it ends with {@link StackOverflowError}. */
    public static class EndlessBean implements LifecycleBean {
        /** {@inheritDoc} */
        @Override public void onLifecycleEvent(LifecycleEventType evt) {
            // No-op.
        }

        /** */
        public EndlessBean getNested() {
            return new EndlessBean();
        }

        /** */
        public void setNested(EndlessBean nested) {
            // No-op.
        }
    }
}
