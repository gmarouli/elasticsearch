/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.action.admin.indices.flush.FlushAction;
import org.elasticsearch.action.admin.indices.refresh.RefreshAction;
import org.elasticsearch.action.downsample.DownsampleAction;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.xpack.aggregatemetric.AggregateMetricMapperPlugin;
import org.elasticsearch.xpack.ccr.Ccr;
import org.elasticsearch.xpack.core.LocalStateCompositeXPackPlugin;
import org.elasticsearch.xpack.core.ilm.LifecyclePolicy;
import org.elasticsearch.xpack.core.ilm.LifecycleSettings;
import org.elasticsearch.xpack.core.ilm.Phase;
import org.elasticsearch.xpack.core.ilm.PhaseCompleteStep;
import org.elasticsearch.xpack.core.ilm.action.ILMActions;
import org.elasticsearch.xpack.core.ilm.action.PutLifecycleRequest;
import org.elasticsearch.xpack.ilm.IndexLifecycle;
import org.junit.After;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Disrupts a downsample operation that ILM runs as part of the {@code downsample} action, while the operation is in flight.
 * <p>
 * Each test parks the operation with {@link DownsampleDisruptionPlugin}, applies a disruption, lets the operation continue and
 * waits for ILM to complete the phase. The resulting downsample index must be identical to a downsample of the same source that
 * ran without disruption. ILM deletes the source index at the end of the action, so that reference is produced up front.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0, numClientNodes = 0)
public class ILMDownsampleDisruptionIT extends DownsamplingIntegTestCase {
    private static final Logger logger = LogManager.getLogger(ILMDownsampleDisruptionIT.class);
    private static final String POLICY_NAME = "mypolicy";
    private static final String SOURCE = "source";
    private static final String TARGET = "downsample-1h-" + SOURCE;
    private static final String REFERENCE = "reference";
    private static final int DATA_NODES = 3;
    private static final int DOC_COUNT = 10_000;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(
            LocalStateCompositeXPackPlugin.class,
            Downsample.class,
            AggregateMetricMapperPlugin.class,
            IndexLifecycle.class,
            Ccr.class,
            DownsampleDisruptionPlugin.class
        );
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put(LifecycleSettings.LIFECYCLE_POLL_INTERVAL, "1s")
            // This is necessary to prevent ILM installing a lifecycle policy, these tests assume a blank slate
            .put(LifecycleSettings.LIFECYCLE_HISTORY_INDEX_ENABLED, false)
            // A shard-level downsample task fails when its source shard relocates, see DownsampleDisruptionIT#testSourceShardRelocation.
            // Rebalancing is disabled so that each test exercises only the disruption it applies.
            .put(EnableAllocationDecider.CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING.getKey(), "none")
            .build();
    }

    @After
    public void resetDisruptionPlugin() {
        DownsampleDisruptionPlugin.reset();
    }

    /**
     * Restarts every node, one at a time, while the shard-level downsample tasks are running.
     */
    public void testILMDownsampleRollingRestart() throws Exception {
        internalCluster().startMasterOnlyNodes(1);
        internalCluster().startDataOnlyNodes(DATA_NODES);
        ensureStableCluster(internalCluster().size());
        final int shards = between(1, 3);
        final DownsampleConfig config = setup(shards, between(0, 1));

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        startIlm();
        awaitParked(shards);

        logger.info("-> restarting all nodes while the shard downsample tasks are running");
        internalCluster().rollingRestart(new InternalTestCluster.RestartCallback());
        ensureStableCluster(internalCluster().size());
        awaitParked(shards);

        DownsampleDisruptionPlugin.release();
        assertIlmDownsampleCompleted(config);
    }

    private enum MasterFailoverPoint {
        /** The shard-level downsample tasks are running. */
        SHARD_TASKS,
        /** All shard-level tasks completed, the downsample index is being refreshed before it is marked as successfully downsampled. */
        REFRESH_TARGET,
        /** The downsample index is marked as successfully downsampled, but the downsample step has not completed. */
        FLUSH_TARGET
    }

    /**
     * Fails over the master while ILM's downsample step is in progress. The new master has to resume the step.
     */
    public void testILMDownsampleMasterFailover() throws Exception {
        internalCluster().startMasterOnlyNodes(3);
        internalCluster().startDataOnlyNodes(DATA_NODES);
        ensureStableCluster(internalCluster().size());
        final int shards = between(1, 3);
        final DownsampleConfig config = setup(shards, between(0, 1));

        final MasterFailoverPoint failoverPoint = randomFrom(MasterFailoverPoint.values());
        logger.info("-> failing over the master at [{}]", failoverPoint);
        final int parked = switch (failoverPoint) {
            case SHARD_TASKS -> {
                DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
                yield shards;
            }
            case REFRESH_TARGET -> {
                DownsampleDisruptionPlugin.park(1, DownsampleDisruptionPlugin.actionOnIndex(RefreshAction.NAME, TARGET));
                yield 1;
            }
            case FLUSH_TARGET -> {
                DownsampleDisruptionPlugin.park(1, DownsampleDisruptionPlugin.actionOnIndex(FlushAction.NAME, TARGET));
                yield 1;
            }
        };
        startIlm();
        awaitParked(parked);

        final String master = internalCluster().getMasterName();
        if (randomBoolean()) {
            logger.info("-> stopping master node [{}]", master);
            internalCluster().stopCurrentMasterNode();
        } else {
            logger.info("-> restarting master node [{}]", master);
            internalCluster().restartNode(master);
        }
        ensureStableCluster(internalCluster().size());

        DownsampleDisruptionPlugin.release();
        assertIlmDownsampleCompleted(config);
    }

    /**
     * Creates the source index, downsamples it without disruption to produce the reference the tests compare against, and
     * creates the ILM policy that downsamples it.
     */
    private DownsampleConfig setup(int shards, int replicas) throws Exception {
        createReadOnlyTimeSeriesIndex(SOURCE, shards, replicas, DOC_COUNT);
        if (randomBoolean()) {
            updateIndexSettings(Settings.builder().put(IndexMetadata.SETTING_INDEX_HIDDEN, randomBoolean()), SOURCE);
        }
        final DownsampleConfig config = new DownsampleConfig(DateHistogramInterval.HOUR, randomSamplingMethod());
        assertAcked(
            client().execute(
                DownsampleAction.INSTANCE,
                new DownsampleAction.Request(TEST_REQUEST_TIMEOUT, SOURCE, REFERENCE, TimeValue.timeValueMinutes(1), config)
            )
        );

        final boolean forceMerge = randomBoolean();
        logger.info("-> creating ILM policy downsampling with [{}], force merge [{}]", config, forceMerge);
        final LifecyclePolicy policy = new LifecyclePolicy(
            POLICY_NAME,
            Map.of(
                "warm",
                new Phase(
                    "warm",
                    TimeValue.ZERO,
                    Map.of(
                        "downsample",
                        new org.elasticsearch.xpack.core.ilm.DownsampleAction(
                            config.getFixedInterval(),
                            null,
                            forceMerge,
                            config.getSamplingMethod()
                        )
                    )
                )
            )
        );
        assertAcked(
            client().execute(ILMActions.PUT, new PutLifecycleRequest(TEST_REQUEST_TIMEOUT, TEST_REQUEST_TIMEOUT, policy)).actionGet()
        );
        return config;
    }

    private void startIlm() {
        logger.info("-> attaching ILM policy [{}] to [{}]", POLICY_NAME, SOURCE);
        updateIndexSettings(Settings.builder().put(LifecycleSettings.LIFECYCLE_NAME, POLICY_NAME), SOURCE);
    }

    /**
     * Waits for ILM to complete the phase, which deletes the source index, and asserts that the downsample index is complete and
     * identical to the reference.
     */
    private void assertIlmDownsampleCompleted(DownsampleConfig config) throws Exception {
        logger.info("-> waiting for ILM to complete the phase for index [{}]", TARGET);
        assertBusy(() -> {
            final IndexMetadata target = indexMetadata(TARGET);
            assertThat(target, notNullValue());
            assertThat(target.getLifecycleExecutionState().step(), equalTo(PhaseCompleteStep.NAME));
            assertFalse(indexExists(SOURCE));
        }, 2, TimeUnit.MINUTES);

        final IndexMetadata target = indexMetadata(TARGET);
        assertThat(IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(target.getSettings()), equalTo(IndexMetadata.DownsampleTaskStatus.SUCCESS));
        assertThat(
            target.getSettings().get(IndexMetadata.INDEX_DOWNSAMPLE_METHOD_KEY),
            equalTo(DownsampleConfig.SamplingMethod.getOrDefault(config.getSamplingMethod()).toString())
        );
        ensureGreen(TARGET);
        assertSameDocuments(REFERENCE, TARGET);
    }

    private static IndexMetadata indexMetadata(String index) {
        return clusterAdmin().prepareState(TEST_REQUEST_TIMEOUT).get().getState().metadata().getProject().index(index);
    }
}
