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
import java.util.Iterator;
import java.util.Map;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.managers.systemview.IgniteConfigurationIterable;
import org.apache.ignite.internal.processors.metric.GridMetricManager;
import org.apache.ignite.internal.util.typedef.T3;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.metric.MetricRegistry;

import static org.apache.ignite.internal.processors.metric.impl.MetricUtils.metricName;

/**
 * Registers the rows of the {@code SYS.CONFIGURATION} system view as metrics: the row {@code A.B.C} is the {@code C}
 * metric of the {@code configuration.A.B} registry. Properties are read once, on registration.
 */
class ConfigurationMetricsRegistrar {
    /** Metric manager. */
    private final GridMetricManager mmgr;

    /** Ignite configuration. */
    private final IgniteConfiguration cfg;

    /** Registered metric registries by names. */
    private final Map<String, MetricRegistry> registries = new HashMap<>();

    /** @param ctx Kernal context. */
    ConfigurationMetricsRegistrar(GridKernalContext ctx) {
        mmgr = ctx.metric();
        cfg = ctx.config();
    }

    /** Registers the metrics from the configuration system view. */
    void init() {
        Iterator<T3<String, Object, Class<?>>> iter = new IgniteConfigurationIterable(cfg).rawIterator();

        while (iter.hasNext())
            registerMetricFromView(iter.next());
    }

    /** */
    private void registerMetricFromView(T3<String, Object, Class<?>> rawProp) {
        String name = rawProp.get1();

        // "Property[i]" -> "Property_i": square brackets are not ideal in metric names.
        String rowName = name.replaceAll("\\[(\\d+)]", "_$1");

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

        registerMetricByType(mreg, propName, rawProp.get2(), rawProp.get3());
    }

    /**
     * Registers a typed metric for a property of a primitive type, a string metric otherwise. Boxed types are string
     * metrics, so a not set value is {@code null}, as in the system view. The metric value is computed once.
     *
     * @param mreg Metric registry.
     * @param name Metric name.
     * @param val Property value, boxed for a primitive type.
     * @param cls Property type: the return type of the getter.
     */
    private static void registerMetricByType(MetricRegistry mreg, String name, Object val, Class<?> cls) {
        if (cls == boolean.class) {
            boolean boolVal = (Boolean)val;

            mreg.register(name, () -> boolVal, null);
        }
        else if (cls == int.class || cls == short.class || cls == byte.class) {
            int intVal = ((Number)val).intValue();

            mreg.register(name, () -> intVal, null);
        }
        else if (cls == long.class) {
            long longVal = (Long)val;

            mreg.register(name, () -> longVal, null);
        }
        else if (cls == double.class || cls == float.class) {
            // By the decimal representation: 0.1f is 0.1, not 0.10000000149011612.
            double dblVal = Double.parseDouble(val.toString());

            mreg.register(name, () -> dblVal, null);
        }
        else {
            String strVal = val != null && val.getClass().isArray() ? S.arrayToString(val) : U.toStringSafe(val);

            mreg.register(name, () -> strVal, String.class, null);
        }
    }

    /**
     * @param name Metric registry name.
     * @return Metric registry, created if absent.
     */
    private MetricRegistry registry(String name) {
        return registries.computeIfAbsent(name, mmgr::registry);
    }

    /** Removes all the registered metric registries. */
    void unregister() {
        for (String regName : registries.keySet())
            mmgr.remove(regName, false);

        registries.clear();
    }
}
