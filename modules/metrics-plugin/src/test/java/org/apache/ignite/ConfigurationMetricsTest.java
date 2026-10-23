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
import org.apache.ignite.internal.systemview.SensitiveProperty;
import org.apache.ignite.lifecycle.LifecycleBean;
import org.apache.ignite.lifecycle.LifecycleEventType;
import org.apache.ignite.plugin.security.SecurityCredentials;
import org.apache.ignite.spi.metric.BooleanMetric;
import org.apache.ignite.spi.metric.DoubleMetric;
import org.apache.ignite.spi.metric.IntMetric;
import org.apache.ignite.spi.metric.LongMetric;
import org.apache.ignite.spi.metric.Metric;
import org.apache.ignite.spi.metric.ObjectMetric;
import org.apache.ignite.spi.metric.ReadOnlyMetricRegistry;
import org.apache.ignite.spi.systemview.view.ConfigurationView;
import org.apache.ignite.testframework.ListeningTestLogger;
import org.apache.ignite.testframework.LogListener;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.MetricsPluginProvider.CONFIGURATION_METRICS;
import static org.apache.ignite.internal.IgniteKernal.CFG_VIEW;
import static org.apache.ignite.internal.processors.metric.impl.MetricUtils.metricName;

/** Tests for the configuration metrics of {@link MetricsPluginProvider}. */
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
            .setPluginProviders(new MetricsPluginProvider(
                new MetricsPluginConfiguration()
                    .setConfigMetricsEnabled(true)))
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
        assertEquals(expected.keySet(), metrics.keySet());

        for (Map.Entry<String, String> e : expected.entrySet())
            assertEquals(e.getKey(), e.getValue(), metrics.get(e.getKey()));
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

    /**
     * Tests that metrics of primitive properties are typed, of other properties, including boxed primitive types,
     * are strings, as in the system view.
     */
    @Test
    public void testFindMetrics() {
        GridMetricManager mmgr = grid(0).context().metric();

        assertEquals(getTestIgniteInstanceName(0),
            mmgr.find(metricName(CONFIGURATION_METRICS, "IgniteInstanceName"), ObjectMetric.class).value());

        assertEquals("{role=test}",
            mmgr.find(metricName(CONFIGURATION_METRICS, "UserAttributes"), ObjectMetric.class).value());

        assertEquals(grid(0).configuration().getPublicThreadPoolSize(),
            mmgr.find(metricName(CONFIGURATION_METRICS, "PublicThreadPoolSize"), IntMetric.class).value());

        assertTrue(mmgr.find(metricName(DEFAULT_REGION, "MetricsEnabled"), BooleanMetric.class).value());

        assertEquals(new DataRegionConfiguration().getEvictionThreshold(),
            mmgr.find(metricName(DEFAULT_REGION, "EvictionThreshold"), DoubleMetric.class).value(), 0);

        assertEquals(MAX_SIZE, mmgr.find(metricName(DEFAULT_REGION, "MaxSize"), LongMetric.class).value());

        assertEquals(7, mmgr.find(metricName(BEAN, "ShortValue"), IntMetric.class).value());

        // By the decimal representation: 0.1f is 0.1, not 0.10000000149011612.
        assertEquals(0.1, mmgr.find(metricName(BEAN, "FloatValue"), DoubleMetric.class).value(), 0);

        // Not set value of a boxed type.
        assertNull(mmgr.find(metricName(BEAN, "BoxedValue"), ObjectMetric.class).value());
    }

    /** Tests that sensitive properties are not registered, even if sensitive data is included in {@code toString()}. */
    @Test
    public void testSensitiveValuesSkipped() {
        Map<String, String> metrics = metrics(grid(0));

        // Marked with @SensitiveProperty.
        assertFalse(metrics.containsKey(metricName(BEAN, "KeyStorePassword")));
        assertFalse(metrics.containsKey(metricName(BEAN, "Credentials", "Password")));

        // char[] property.
        assertFalse(metrics.containsKey(metricName(BEAN, "TrustStorePassword")));

        // Other properties of a nested object are registered.
        assertEquals("login", metrics.get(metricName(BEAN, "Credentials", "Login")));

        // No metric exposes the secret.
        for (Map.Entry<String, String> m : metrics.entrySet())
            assertFalse(m.getKey(), String.valueOf(m.getValue()).contains(SECRET));
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
        private char[] trustStorePwd = SECRET.toCharArray();

        /** */
        private Integer boxedVal;

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
        @SensitiveProperty
        public String getKeyStorePassword() {
            return keyStorePwd;
        }

        /** */
        public void setKeyStorePassword(String keyStorePwd) {
            this.keyStorePwd = keyStorePwd;
        }

        /** */
        public char[] getTrustStorePassword() {
            return trustStorePwd;
        }

        /** */
        public void setTrustStorePassword(char[] trustStorePwd) {
            this.trustStorePwd = trustStorePwd;
        }

        /** */
        public Integer getBoxedValue() {
            return boxedVal;
        }

        /** */
        public void setBoxedValue(Integer boxedVal) {
            this.boxedVal = boxedVal;
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
}
