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

import java.io.Serializable;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.plugin.CachePluginContext;
import org.apache.ignite.plugin.CachePluginProvider;
import org.apache.ignite.plugin.ExtensionRegistry;
import org.apache.ignite.plugin.IgnitePlugin;
import org.apache.ignite.plugin.PluginContext;
import org.apache.ignite.plugin.PluginProvider;
import org.apache.ignite.spi.metric.MetricExporterSpi;
import org.jetbrains.annotations.Nullable;

/**
 * Plugin that adds extra metrics to the node, exported by {@link MetricExporterSpi} as any other metric. Features are
 * enabled in {@link MetricsPluginConfiguration}, all are disabled by default:
 * <ul>
 *     <li>Configuration metrics: the node configuration as the {@value #CONFIGURATION_METRICS} registry tree.</li>
 * </ul>
 */
public class MetricsPluginProvider implements PluginProvider<MetricsPluginConfiguration> {
    /** Root registry of the configuration metrics. */
    public static final String CONFIGURATION_METRICS = "configuration";

    /** */
    private final MetricsPluginConfiguration cfg;

    /** */
    private GridKernalContext ctx;

    /** {@code null} if the configuration metrics are not registered. */
    private ConfigurationMetricsRegistrar registrar;

    /** @param cfg Plugin configuration. */
    public MetricsPluginProvider(MetricsPluginConfiguration cfg) {
        this.cfg = cfg;
    }

    /** {@inheritDoc} */
    @Override public String name() {
        return "MetricsPlugin";
    }

    /** {@inheritDoc} */
    @Override public String version() {
        return "1.0.0";
    }

    /** {@inheritDoc} */
    @Override public <T extends IgnitePlugin> T plugin() {
        return (T)new IgnitePlugin() {
            // No-op.
        };
    }

    /** {@inheritDoc} */
    @Override public String copyright() {
        return "";
    }

    /** {@inheritDoc} */
    @Override public void initExtensions(PluginContext pluginCtx, ExtensionRegistry registry) {
        ctx = ((IgniteEx)pluginCtx.grid()).context();
    }

    /** {@inheritDoc} */
    @Nullable @Override public <T> T createComponent(PluginContext ctx, Class<T> cls) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public CachePluginProvider createCacheProvider(CachePluginContext ctx) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void start(PluginContext pluginCtx) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) {
        if (registrar != null)
            registrar.unregister();

        // Don't retain the stopped node, the provider may outlive it in the user configuration.
        registrar = null;
        ctx = null;
    }

    /** {@inheritDoc} */
    @Override public void onIgniteStart() {
        initConfigMetricsIfNecessary();
    }

    /** Registers the configuration metrics, if enabled. */
    private void initConfigMetricsIfNecessary() {
        if (cfg.isConfigMetricsEnabled()) {
            registrar = new ConfigurationMetricsRegistrar(ctx);

            try {
                registrar.init();
            }
            catch (Throwable e) {
                if (e instanceof OutOfMemoryError)
                    throw e;

                registrar.unregister();

                registrar = null;

                U.error(ctx.log(getClass()), "Failed to register the configuration metrics, " +
                    "the configuration will not be exposed as metrics.", e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public void onIgniteStop(boolean cancel) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Nullable @Override public Serializable provideDiscoveryData(UUID nodeId) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public void receiveDiscoveryData(UUID nodeId, Serializable data) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void validateNewNode(ClusterNode node) {
        // No-op.
    }
}
