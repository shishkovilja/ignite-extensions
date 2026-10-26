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

import java.util.Iterator;
import java.util.regex.Pattern;
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
    /** */
    private static final Pattern IDX_PATTERN = Pattern.compile("\\[(\\d+)]");

    /** */
    private ConfigurationMetricsRegistrar() {
        // No-op.
    }

    /** @param ctx Kernal context. */
    static void register(GridKernalContext ctx) {
        GridMetricManager mmgr = ctx.metric();

        Iterator<T3<String, Object, Class<?>>> iter = new IgniteConfigurationIterable(ctx.config()).rawIterator();

        while (iter.hasNext()) {
            T3<String, Object, Class<?>> rawProp = iter.next();

            // "Property[i]" -> "Property_i".
            String rowName = IDX_PATTERN.matcher(rawProp.get1()).replaceAll("_$1");

            int dotIdx = rowName.lastIndexOf('.');

            String regName = dotIdx < 0
                ? MetricsPluginProvider.CONFIGURATION_METRICS
                : metricName(MetricsPluginProvider.CONFIGURATION_METRICS, rowName.substring(0, dotIdx));

            registerMetricByType(mmgr.registry(regName), rowName.substring(dotIdx + 1), rawProp.get2(),
                rawProp.get3());
        }
    }

    /** Registers a typed metric for a primitive type, a string metric otherwise, including boxed types. */
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
            // 0.1f -> 0.1, not 0.10000000149011612.
            double dblVal = Double.parseDouble(val.toString());

            mreg.register(name, () -> dblVal, null);
        }
        else {
            String strVal = val != null && val.getClass().isArray() ? S.arrayToString(val) : U.toStringSafe(val);

            mreg.register(name, () -> strVal, String.class, null);
        }
    }
}
