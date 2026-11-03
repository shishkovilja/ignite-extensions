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
import java.util.List;
import java.util.function.Consumer;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import org.apache.ignite.Ignite;
import org.apache.ignite.cache.CacheKeyConfiguration;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.failure.AbstractFailureHandler;
import org.apache.ignite.failure.FailureContext;
import org.apache.ignite.plugin.AbstractTestPluginProvider;
import org.apache.ignite.util.GridCommandHandlerAbstractTest;
import org.junit.Test;

import static org.apache.ignite.internal.commandline.CommandHandler.EXIT_CODE_OK;
import static org.apache.ignite.testframework.GridTestUtils.assertContains;
import static org.apache.ignite.testframework.GridTestUtils.assertNotContains;
import static org.apache.ignite.testframework.GridTestUtils.waitForCondition;
import static org.junit.Assert.assertNotEquals;

/** */
public class CheckConfigOnlyDiffTest extends GridCommandHandlerAbstractTest {
    /** */
    private static final String CMD = "--check-config";

    /** */
    private static final String ONLY_DIFF = "--only-diff";

    /** */
    private static final String NO_CFG_DIFFS = "=== Total configuration differences: 0 ===";

    /** */
    private Consumer<IgniteConfiguration> cfgOverride = cfg -> { };

    /** */
    private boolean multiJvm;

    /** */
    private List<String> jvmArgs = new ArrayList<>();

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        persistenceEnable(false);

        injectTestSystemOut();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        super.afterTest();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfgOverride.accept(cfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected boolean isMultiJvm() {
        return multiJvm;
    }

    /** {@inheritDoc} */
    @Override protected List<String> additionalRemoteJvmArgs() {
        List<String> args = super.additionalRemoteJvmArgs();

        args.addAll(jvmArgs);

        return args;
    }

    /** Excluded properties must not be reported, clients must not be compared. */
    @Test
    public void testNoDifferences() throws Exception {
        cfgOverride = cfg ->
            cfg.setFailureDetectionTimeout(CLIENT_NODE_NAME_PREFIX
                    .equals(cfg.getIgniteInstanceName()) ? 30_000 : 10_000)
                .setCacheConfiguration(
                    new CacheConfiguration<>("cache")
                        .setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(Duration.ONE_DAY)));

        startGrids(3);

        startClientGrid(CLIENT_NODE_NAME_PREFIX);

        assertEquals(EXIT_CODE_OK, execute(CMD, ONLY_DIFF));

        assertContains(log, testOut.toString(), NO_CFG_DIFFS);
    }

    /** */
    @Test
    public void testOneNodeDiffers() throws Exception {
        cfgOverride = cfg -> cfg.setFailureDetectionTimeout(idx(cfg) == 2 ? 30_000 : 10_000);

        startGrids(3);

        assertEquals(EXIT_CODE_OK, execute(CMD, ONLY_DIFF));

        assertContains(log, testOut.toString(), """
            === Total configuration differences: 1 ===

            === IgniteConfiguration: 1 found ===

            --- FailureDetectionTimeout ---
            Value    Nodes (consistentId / Host)            Count
            10000    gridCommandHandlerTest0 / 127.0.0.1        2
                     gridCommandHandlerTest1 / 127.0.0.1        \s
            30000    gridCommandHandlerTest2 / 127.0.0.1        1

            === IgniteSystemProperties: 0 found ===

            === Plugins: 0 found ===
            """);
    }

    /** System properties are per JVM, so the nodes are started in separate ones. */
    @Test
    public void testSystemProperties() throws Exception {
        multiJvm = true;

        startGrid(0);

        for (int i = 1; i <= 2; i++) {
            // ENTRY_LOCK_TIMEOUT is declared out of IgniteSystemProperties.
            jvmArgs = List.of("-DIGNITE_EXCHANGE_HISTORY_SIZE=" + i * 100, "-DENTRY_LOCK_TIMEOUT=" + (1000 + i),
                "-Dmy.custom.property=" + i);

            startGrid(i);
        }

        assertTrue(waitForCondition(() -> execute(CMD, ONLY_DIFF) == EXIT_CODE_OK, getTestTimeout()));

        String out = testOut.toString();

        assertContains(log, out, """
            --- IGNITE_EXCHANGE_HISTORY_SIZE ---
            Value       Nodes (consistentId / Host)            Count
            <absent>    gridCommandHandlerTest0 / 127.0.0.1        1
            100         gridCommandHandlerTest1 / 127.0.0.1        1
            200         gridCommandHandlerTest2 / 127.0.0.1        1
            """);

        assertContains(log, out, """
            --- ENTRY_LOCK_TIMEOUT ---
            Value       Nodes (consistentId / Host)            Count
            <absent>    gridCommandHandlerTest0 / 127.0.0.1        1
            1001        gridCommandHandlerTest1 / 127.0.0.1        1
            1002        gridCommandHandlerTest2 / 127.0.0.1        1
            """);

        assertNotContains(log, out, "my.custom.property");
    }

    /** */
    @Test
    public void testPropertiesOnPartOfNodes() throws Exception {
        cfgOverride = cfg -> {
            if (idx(cfg) == 0)
                cfg.setCacheConfiguration(new CacheConfiguration<>("orders").setBackups(1));

            // An empty array differs from null.
            if (idx(cfg) == 2)
                cfg.setCacheKeyConfiguration(new CacheKeyConfiguration[0]);
        };

        startGrids(3);

        assertEquals(EXIT_CODE_OK, execute(CMD, ONLY_DIFF));

        String out = testOut.toString();

        assertContains(log, out, """
            --- CacheConfiguration[orders].Backups ---
            Value       Nodes (consistentId / Host)            Count
            <absent>    gridCommandHandlerTest1 / 127.0.0.1        2
                        gridCommandHandlerTest2 / 127.0.0.1        \s
            1           gridCommandHandlerTest0 / 127.0.0.1        1
            """);

        assertContains(log, out, """
            --- CacheKeyConfiguration ---
            Value       Nodes (consistentId / Host)            Count
            <absent>    gridCommandHandlerTest0 / 127.0.0.1        2
                        gridCommandHandlerTest1 / 127.0.0.1        \s
            []          gridCommandHandlerTest2 / 127.0.0.1        1
            """);
    }

    /** */
    @Test
    public void testPlugins() throws Exception {
        cfgOverride = cfg -> {
            if (idx(cfg) == 0)
                cfg.setPluginProviders(new TestPluginProvider("custom-plugin", "1.2.0"));
            else if (idx(cfg) == 1)
                cfg.setPluginProviders(new TestPluginProvider("custom-plugin", "1.3.0"));
        };

        startGrids(3);

        assertEquals(EXIT_CODE_OK, execute(CMD, ONLY_DIFF));

        String out = testOut.toString();

        assertContains(log, out, """
            --- custom-plugin.Version ---
            Value       Nodes (consistentId / Host)            Count
            1.2.0       gridCommandHandlerTest0 / 127.0.0.1        1
            1.3.0       gridCommandHandlerTest1 / 127.0.0.1        1
            <absent>    gridCommandHandlerTest2 / 127.0.0.1        1
            """);

        assertContains(log, out, """
            --- custom-plugin.ClassName ---
            Value                                                    Nodes (consistentId / Host)            Count
            org.apache.ignite.plugin.AbstractTestPluginProvider$1    gridCommandHandlerTest0 / 127.0.0.1        2
                                                                     gridCommandHandlerTest1 / 127.0.0.1        \s
            <absent>                                                 gridCommandHandlerTest2 / 127.0.0.1        1
            """);
    }

    /** */
    @Test
    public void testSingleServerNode() throws Exception {
        startGrid(0);

        assertEquals(EXIT_CODE_OK, execute(CMD, ONLY_DIFF));

        assertContains(log, testOut.toString(), "Nothing to compare: only 1 server node available.");
    }

    /** */
    @Test
    public void testJobFailure() throws Exception {
        cfgOverride = cfg -> {
            if (idx(cfg) == 1)
                cfg.setFailureHandler(new FailingGetterHandler());
        };

        startGrids(2);

        assertNotEquals(EXIT_CODE_OK, execute(CMD, ONLY_DIFF));

        assertContains(log, testOut.toString(), "Failed to execute job [nodeId=" + grid(1).localNode().id());
    }

    /** */
    private int idx(IgniteConfiguration cfg) {
        return getTestIgniteInstanceIndex(cfg.getIgniteInstanceName());
    }

    /** */
    private static class TestPluginProvider extends AbstractTestPluginProvider {
        /** */
        private final String name;

        /** */
        private final String ver;

        /** */
        TestPluginProvider(String name, String ver) {
            this.name = name;
            this.ver = ver;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return name;
        }

        /** {@inheritDoc} */
        @Override public String version() {
            return ver;
        }
    }

    /** The system view calls getters of a bean only when it is read. */
    public static class FailingGetterHandler extends AbstractFailureHandler {
        /** */
        public int getFailingProperty() {
            throw new IllegalStateException("Test failure");
        }

        /** */
        public void setFailingProperty(int val) {
            // No-op.
        }

        /** {@inheritDoc} */
        @Override protected boolean handle(Ignite ignite, FailureContext failureCtx) {
            return false;
        }
    }
}
