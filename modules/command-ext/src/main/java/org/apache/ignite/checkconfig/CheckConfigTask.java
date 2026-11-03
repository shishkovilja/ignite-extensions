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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.internal.management.SystemViewCommand;
import org.apache.ignite.internal.processors.task.GridInternal;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.visor.VisorJob;
import org.apache.ignite.internal.visor.VisorMultiNodeTask;
import org.jetbrains.annotations.Nullable;

import static java.util.Comparator.comparingInt;
import static java.util.Map.Entry.comparingByValue;
import static org.apache.ignite.internal.management.SystemViewTask.SimpleType.NUMBER;
import static org.apache.ignite.internal.management.SystemViewTask.SimpleType.STRING;

/** Collects configuration and builds a report of differences. */
@GridInternal
public class CheckConfigTask extends VisorMultiNodeTask<CheckConfigCommandArg, String, CheckConfigJobResult> {
    /** */
    private static final long serialVersionUID = 0;

    /** Fields that are node-specific. */
    private static final Set<String> EXCLUDED_FIELDS = Set.of(
        "IgniteInstanceName", "GridName", "ConsistentId", "NodeId", "LocalHost", "LocalAddress", "LocalPort", "Host",
        "UserAttributes",

        // Cannot be compared due to the default implementation of toString.
        "MBeanServer", "ExpiryPolicyFactory");

    /** System properties that are node-specific. */
    private static final Set<String> EXCLUDED_PROPS = Set.of(
        "IGNITE_OVERRIDE_CONSISTENT_ID", "IGNITE_LOCAL_HOST", "IGNITE_JETTY_HOST", "IGNITE_SSH_HOST",
        "IGNITE_TCP_DISCOVERY_ADDRESSES", "IGNITE_DATA_CENTER_ID", "IGNITE_SUCCESS_FILE", "IGNITE_PROG_NAME");

    /** {@inheritDoc} */
    @Override protected VisorJob<CheckConfigCommandArg, CheckConfigJobResult> job(CheckConfigCommandArg arg) {
        return new CheckConfigJob(arg, debug);
    }

    /** {@inheritDoc} */
    @Override protected String reduce0(List<ComputeJobResult> results) throws IgniteException {
        Map<String, CheckConfigJobResult> nodesRes = new TreeMap<>();

        for (ComputeJobResult res : results) {
            ClusterNode node = res.getNode();

            if (res.getException() != null)
                throw new IgniteException("Failed to execute job [nodeId=" + node.id() + ']', res.getException());

            // Host names are absent for a node bound to a loopback address or with IGNITE_LOCAL_HOST set.
            Collection<String> hosts = F.isEmpty(node.hostNames()) ? node.addresses() : node.hostNames();

            nodesRes.put(node.consistentId() + " / " + String.join(",", hosts), res.getData());
        }

        return taskResult(nodesRes);
    }

    /** @param nodesRes Per-node results. */
    static String taskResult(Map<String, CheckConfigJobResult> nodesRes) {
        if (nodesRes.size() < 2)
            return "Nothing to compare: only " + nodesRes.size() + " server node available.";

        List<String> out = new ArrayList<>();

        int totalDiffCnt = printSection(out, nodesRes, "IgniteConfiguration", CheckConfigJobResult::config,
            CheckConfigTask::excludedField);

        totalDiffCnt += printSection(out, nodesRes, "IgniteSystemProperties", CheckConfigJobResult::systemProperties,
            EXCLUDED_PROPS::contains);

        totalDiffCnt += printSection(out, nodesRes, "Plugins", CheckConfigJobResult::plugins, null);

        return String.join(U.nl(),
            F.concat(false,
                List.of("=== Total configuration differences: " + totalDiffCnt + " ===", ""),
                out));
    }

    /**
     * @param out Output.
     * @param nodesRes Per-node results.
     * @param title Title of a section.
     * @param sectionProps Gets properties of the section from a node result.
     * @param exclusionFilter Filter of excluded properties.
     * @return Number of different properties.
     */
    static int printSection(
        List<String> out,
        Map<String, CheckConfigJobResult> nodesRes,
        String title,
        Function<CheckConfigJobResult, Map<String, String>> sectionProps,
        @Nullable Predicate<String> exclusionFilter
    ) {
        Map<String, List<List<?>>> diff = diff(nodesRes, sectionProps, exclusionFilter);

        String titleInfo = String.format("=== %s: %d found ===", title, diff.size());

        out.add(titleInfo);

        diff.forEach((propName, rows) -> {
            out.add("");

            out.add("--- " + propName + " ---");

            SystemViewCommand.printTable(
                List.of("Value", "Nodes (consistentId / Host)", "Count"),
                List.of(STRING, STRING, NUMBER),
                rows,
                out::add);
        });

        out.add("");

        return diff.size();
    }

    /** @return Table rows by names of different properties. */
    private static Map<String, List<List<?>>> diff(
        Map<String, CheckConfigJobResult> nodesRes,
        Function<CheckConfigJobResult, Map<String, String>> sectionProps,
        @Nullable Predicate<String> exclusionFilter
    ) {
        Set<String> allPropsNames = new TreeSet<>();

        nodesRes.values().forEach(res -> allPropsNames.addAll(sectionProps.apply(res).keySet()));

        if (exclusionFilter != null)
            allPropsNames.removeIf(exclusionFilter);

        Map<String, List<List<?>>> diffRes = new LinkedHashMap<>();

        // Group nodes by values of a property.
        // If the value is the same on all nodes, there is a single group - no differences will be reported.
        for (String propName : allPropsNames) {
            List<List<?>> rows = new ArrayList<>();

            Map<String, List<String>> propGrps = new LinkedHashMap<>();

            nodesRes.forEach((node, nodeRes) -> {
                String propVal = sectionProps.apply(nodeRes).getOrDefault(propName, "<absent>");

                propGrps.computeIfAbsent(propVal, v -> new ArrayList<>()).add(node);
            });

            if (propGrps.size() > 1) {
                List<Map.Entry<String, List<String>>> grps = new ArrayList<>(propGrps.entrySet());

                // Sort from the biggest group to the smallest one.
                grps.sort(comparingByValue(comparingInt(nodes -> -nodes.size())));

                grps.forEach(grp -> {
                    List<String> nodes = grp.getValue();

                    rows.add(List.of(grp.getKey(), nodes.get(0), nodes.size()));

                    // Multiline output for the rest of the nodes.
                    for (int i = 1; i < nodes.size(); i++)
                        rows.add(List.of("", nodes.get(i), ""));
                });

                diffRes.put(propName, rows);
            }
        }

        return diffRes;
    }

    /** @param propName Property name. */
    private static boolean excludedField(String propName) {
        String[] split = propName.split("\\.");

        return EXCLUDED_FIELDS.contains(split[split.length - 1]);
    }
}
