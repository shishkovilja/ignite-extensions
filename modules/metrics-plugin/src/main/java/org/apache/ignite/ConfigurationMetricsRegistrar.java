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

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.processors.metric.GridMetricManager;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.metric.MetricRegistry;
import org.apache.ignite.spi.systemview.view.ConfigurationView;
import org.apache.ignite.spi.systemview.view.SystemView;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.apache.ignite.internal.processors.metric.impl.MetricUtils.metricName;

/**
 * Registers the rows of the {@code SYS.CONFIGURATION} system view as metrics: the row {@code A.B.C} is the {@code C}
 * metric of the {@code configuration.A.B} registry. Values are read once, on registration.
 */
class ConfigurationMetricsRegistrar {
    /** Sensitive property name: ends with one of the listed words, case-insensitive. */
    private static final Pattern SENSITIVE_NAME = Pattern.compile(".*(AccountKey|Credential|Credentials|" +
        "CredentialsProvider|Identity|KeyPair|Login|Passwd|Password|Pwd|Secret|SecretKey|Token|User|UserObject)",
        CASE_INSENSITIVE);

    /** Array element index in a view row name: {@code Property[i]}. */
    private static final Pattern ARRAY_IDX = Pattern.compile("\\[\\d+]$");

    /** Skip sensitive properties, if they are excluded from {@code toString()}, see {@link S#includeSensitive()}. */
    private final boolean excludeSensitiveData = !S.includeSensitive();

    /** Configuration system view. */
    private final SystemView<ConfigurationView> cfgView;

    /** Metric manager. */
    private final GridMetricManager mmgr;

    /** Registered metric registries by names. */
    private final Map<String, MetricRegistry> registries = new HashMap<>();

    /** @param ctx Kernal context. */
    ConfigurationMetricsRegistrar(GridKernalContext ctx) {
        cfgView = ctx.systemView().view(IgniteKernal.CFG_VIEW);
        mmgr = ctx.metric();
    }

    /** Registers the metrics from the configuration system view. */
    void init() {
        for (ConfigurationView row : cfgView)
            registerMetricFromView(row);
    }

    /**
     * Registers the view row as a metric, creating the registries on its path.
     *
     * @param row Configuration view row.
     */
    private void registerMetricFromView(ConfigurationView row) {
        // Like fields marked with `@GridToStringInclude(sensitive = true)` in `toString()`.
        if (excludeSensitiveData && isSensitive(row.name()))
            return;

        // "Property[i]" -> "Property_i": square brackets are not ideal in metric names.
        String rowName = row.name().replaceAll("\\[(\\d+)]", "_$1");

        String[] splitName = rowName.split("\\.");

        String curMregName = ConfigurationMetricsPluginProvider.CONFIGURATION_METRICS;

        // The last part is the property, the preceding parts are the registries.
        int propIdx = splitName.length - 1;
        String propName = splitName[propIdx];

        MetricRegistry mreg = registry(curMregName);

        for (int i = 0; i < propIdx; i++) {
            curMregName = metricName(curMregName, splitName[i]);

            mreg = registry(curMregName);
        }

        String val = row.value();

        mreg.register(propName, () -> val, String.class, "Configuration property " + row.name());
    }

    /**
     * @param name Metric registry name.
     * @return Metric registry, created if absent.
     */
    private MetricRegistry registry(String name) {
        return registries.computeIfAbsent(name, mmgr::registry);
    }

    /**
     * Checks every part of the path, so a nested object of a sensitive property is sensitive as a whole:
     * {@code Secret.Value} and {@code SecretKey[0].Value} are sensitive.
     *
     * @param path Property path, as in the view: {@code A.B[0].C}.
     * @return {@code True} if the property is sensitive.
     */
    static boolean isSensitive(String path) {
        for (String name : path.split("\\.")) {
            // "Secret[0]" -> "Secret".
            if (SENSITIVE_NAME.matcher(ARRAY_IDX.matcher(name).replaceAll("")).matches())
                return true;
        }

        return false;
    }

    /** Removes all the registered metric registries. */
    void unregister() {
        for (String regName : registries.keySet())
            mmgr.remove(regName, false);

        registries.clear();
    }
}
