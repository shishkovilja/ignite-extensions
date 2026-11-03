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

package org.apache.ignite.checkconfig;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.SystemProperty;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.plugin.IgnitePluginProcessor;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.spi.systemview.view.ConfigurationView;
import org.apache.ignite.spi.systemview.view.PluginView;
import org.apache.ignite.spi.systemview.view.SystemView;
import org.apache.ignite.startup.cmdline.CommandLineStartup;

import static org.apache.ignite.internal.IgniteKernal.CFG_VIEW;

/** */
class CheckConfigJobResultBuilder {
    /** */
    private final IgniteEx ignite;

    /** */
    private final boolean onlyDiff;

    /**
     * @param ignite Ignite.
     * @param onlyDiff Print only differences in configuration.
     */
    CheckConfigJobResultBuilder(IgniteEx ignite, boolean onlyDiff) {
        this.ignite = ignite;
        this.onlyDiff = onlyDiff;
    }

    /** */
    CheckConfigJobResult build() {
        return new CheckConfigJobResult(configuration(), systemProperties(), plugins());
    }

    /** */
    Map<String, String> configuration() {
        Map<String, String> rows = new HashMap<>();

        ignite.context().systemView().<ConfigurationView>view(CFG_VIEW).iterator()
            .forEachRemaining(view -> rows.put(view.name(), view.value()));

        return normalize(rows);
    }

    /** @param rows Rows to normalize. */
    private static Map<String, String> normalize(Map<String, String> rows) {
        Map<String, String> normalizedCfg = new HashMap<>();

        rows.forEach((name, val) -> {
            if (val != null)
                normalizedCfg.put(replaceIndexWithName(name, rows), val);
        });

        return normalizedCfg;
    }

    /**
     * Replaces the index of a cache, service or data region with its name for readability:
     * {@code CacheConfiguration[2].Backups} is converted to {@code CacheConfiguration[orders].Backups}.
     */
    private static String replaceIndexWithName(String prop, Map<String, String> rows) {
        if (prop.startsWith("CacheConfiguration[") || prop.startsWith("ServiceConfiguration[")
            || prop.startsWith("DataStorageConfiguration.DataRegionConfigurations[")) {
            int end = prop.indexOf(']');

            // `Name` property of a cache or data region, etc.
            String elName = rows.get(prop.substring(0, end + 1) + ".Name");

            if (elName != null)
                return prop.substring(0, prop.indexOf('[') + 1) + elName + prop.substring(end);
        }

        return prop;
    }

    /** */
    Map<String, String> systemProperties() {
        Map<String, String> res = new HashMap<>();

        for (String name : systemPropertyNames()) {
            String val = IgniteSystemProperties.getString(name);

            if (val != null)
                res.put(name, val);
        }

        return res;
    }

    /** @return Values of {@link SystemProperty} constants, found the same way as for {@code ignite.sh -systemProps}. */
    Set<String> systemPropertyNames() {
        Set<String> res = new HashSet<>();

        try {
            List<Class<?>> classes = U.staticField(CommandLineStartup.class, "PROPS_CLS");

            for (Class<?> cls : classes) {
                for (Field field : cls.getFields()) {
                    if (field.isAnnotationPresent(SystemProperty.class))
                        res.add(U.staticField(cls, field.getName()));
                }
            }
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to get names of system properties", e);
        }

        return res;
    }

    /** */
    Map<String, String> plugins() {
        SystemView<PluginView> view = ignite.context().systemView().view(IgnitePluginProcessor.PLUGINS_SYS_VIEW);

        if (view == null)
            throw new IgniteException("Plugins system view is not registered yet, the node may be still starting, " +
                "retry later");

        Map<String, String> res = new HashMap<>();

        for (PluginView plugin : view) {
            res.put(plugin.name() + ".ClassName", plugin.className());

            if (plugin.version() != null)
                res.put(plugin.name() + ".Version", plugin.version());
        }

        return res;
    }
}
