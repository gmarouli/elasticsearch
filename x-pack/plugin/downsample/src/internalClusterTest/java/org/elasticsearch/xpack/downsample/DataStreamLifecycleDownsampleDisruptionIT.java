/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.action.admin.indices.flush.FlushAction;
import org.elasticsearch.action.admin.indices.refresh.RefreshAction;
import org.elasticsearch.action.admin.indices.rollover.RolloverAction;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.action.downsample.DownsampleAction;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.cluster.metadata.DataStreamLifecycle;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.datastreams.lifecycle.DataStreamLifecycleService;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.After;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Disrupts a downsample operation that the data stream lifecycle runs, while the operation is in flight.
 * <p>
 * Each test parks the operation with {@link DownsampleDisruptionPlugin}, applies a disruption, lets the operation continue and
 * waits for the data stream lifecycle to replace the source backing index with the downsample index. The downsample index must
 * be identical to a downsample of the same source that ran without disruption. The data stream lifecycle deletes the source
 * index once it is replaced, so that reference is produced while the operation is parked.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0, numClientNodes = 0)
public class DataStreamLifecycleDownsampleDisruptionIT extends DownsamplingIntegTestCase {
    private static final Logger logger = LogManager.getLogger(DataStreamLifecycleDownsampleDisruptionIT.class);
    private static final String DATA_STREAM = "metrics-foo";
    private static final String REFERENCE = "reference";
    private static final int DATA_NODES = 3;
    // Every document can end up in a bucket of its own, this keeps the downsample index below the 10k documents a search returns
    private static final int DOC_COUNT = 8_000;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(DownsampleDisruptionPlugin.class);
        return plugins;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            .put(DataStreamLifecycleService.DATA_STREAM_LIFECYCLE_POLL_INTERVAL, "1s")
            // A shard-level downsample task fails when its source shard relocates, see DownsampleDisruptionIT#testSourceShardRelocation.
            // Rebalancing is disabled so that each test exercises only the disruption it applies. It also avoids shard relocations
            // timing out the `ensureGreen` calls, see #131394.
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
    public void testDataStreamLifecycleDownsampleRollingRestart() throws Exception {
        startCluster(1);
        final int shards = between(1, 3);
        final DownsampleConfig config = new DownsampleConfig(new DateHistogramInterval("5m"), randomSamplingMethod());
        final String sourceIndex = setupDataStream(shards, between(0, 1), config, null);
        final String targetIndex = "downsample-5m-" + sourceIndex;

        parkShardTasksAndProduceReference(sourceIndex, shards, config, this::rollover);

        logger.info("-> restarting all nodes while the shard downsample tasks are running");
        internalCluster().rollingRestart(new InternalTestCluster.RestartCallback());
        ensureStableCluster(internalCluster().size());
        awaitParked(shards);

        DownsampleDisruptionPlugin.release();
        assertDataStreamDownsampled(targetIndex, sourceIndex);
    }

    private enum MasterFailoverPoint {
        /** The shard-level downsample tasks are running. */
        SHARD_TASKS,
        /** All shard-level tasks completed, the downsample index is being refreshed before it is marked as successfully downsampled. */
        REFRESH_TARGET,
        /**
         * The downsample index is marked as successfully downsampled, the downsample request has not completed. The data stream
         * lifecycle does not wait for the request to complete, it replaces the source index in its next run once it sees the status,
         * so the failover happens right before or right after the replacement.
         */
        FLUSH_TARGET
    }

    /**
     * Fails over the master while the downsample operation the data stream lifecycle started is in progress. The data stream
     * lifecycle of the new master has to pick up the operation and replace the source index once it completes.
     */
    public void testDataStreamLifecycleDownsampleMasterFailover() throws Exception {
        startCluster(3);
        final int shards = between(1, 3);
        final DownsampleConfig config = new DownsampleConfig(new DateHistogramInterval("5m"), randomSamplingMethod());
        final String sourceIndex = setupDataStream(shards, between(0, 1), config, null);
        final String targetIndex = "downsample-5m-" + sourceIndex;

        final MasterFailoverPoint failoverPoint = randomFrom(MasterFailoverPoint.values());
        logger.info("-> failing over the master at [{}]", failoverPoint);
        switch (failoverPoint) {
            case SHARD_TASKS -> parkShardTasksAndProduceReference(sourceIndex, shards, config, this::rollover);
            case REFRESH_TARGET, FLUSH_TARGET -> {
                // The reference has to be produced while the source index still exists, so the shard-level tasks are parked first
                parkShardTasksAndProduceReference(sourceIndex, shards, config, this::rollover);
                final String action = failoverPoint == MasterFailoverPoint.REFRESH_TARGET ? RefreshAction.NAME : FlushAction.NAME;
                DownsampleDisruptionPlugin.park(1, DownsampleDisruptionPlugin.actionOnIndex(action, targetIndex));
                DownsampleDisruptionPlugin.releaseParked();
                awaitParked(1);
            }
        }

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
        assertDataStreamDownsampled(targetIndex, sourceIndex);
    }

    /**
     * Configures two downsampling rounds and restarts a node that runs a shard-level task of the second round, which downsamples
     * the downsample index of the first round.
     */
    public void testDataStreamLifecycleMultiRoundDownsampleNodeRestart() throws Exception {
        startCluster(1);
        final int shards = between(1, 3);
        final DownsampleConfig.SamplingMethod samplingMethod = randomSamplingMethod();
        final DownsampleConfig config = new DownsampleConfig(new DateHistogramInterval("10m"), samplingMethod);
        final String sourceIndex = setupDataStream(
            shards,
            between(0, 1),
            new DownsampleConfig(new DateHistogramInterval("5m"), samplingMethod),
            new DataStreamLifecycle.DownsamplingRound(TimeValue.timeValueSeconds(10), config.getFixedInterval())
        );
        final String firstRoundIndex = "downsample-5m-" + sourceIndex;
        final String secondRoundIndex = "downsample-10m-" + sourceIndex;

        // The second round downsamples the first round's index, so this parks only the second round
        parkShardTasksAndProduceReference(firstRoundIndex, shards, config, this::rollover);

        final String victim = randomFrom(DownsampleDisruptionPlugin.parkedOnNodes());
        logger.info("-> restarting node [{}] running a shard downsample task of the second round", victim);
        internalCluster().restartNode(victim);
        ensureStableCluster(internalCluster().size());
        awaitParked(shards);

        DownsampleDisruptionPlugin.release();
        assertDataStreamDownsampled(secondRoundIndex, firstRoundIndex);
        assertFalse(indexExists(sourceIndex));
    }

    private void startCluster(int masterNodes) {
        internalCluster().startMasterOnlyNodes(masterNodes);
        internalCluster().startDataOnlyNodes(DATA_NODES);
        ensureStableCluster(masterNodes + DATA_NODES);
        ensureGreen();
    }

    /**
     * Creates the data stream with a lifecycle that downsamples with {@code config} right after rollover, followed by
     * {@code secondRound} if it is not null, and ingests the documents into its first backing index.
     *
     * @return the name of the first backing index, the one the data stream lifecycle downsamples after {@link #rollover()}
     */
    private String setupDataStream(int shards, int replicas, DownsampleConfig config, DataStreamLifecycle.DownsamplingRound secondRound)
        throws Exception {
        final List<DataStreamLifecycle.DownsamplingRound> rounds = new ArrayList<>();
        rounds.add(new DataStreamLifecycle.DownsamplingRound(TimeValue.timeValueMillis(0), config.getFixedInterval()));
        if (secondRound != null) {
            rounds.add(secondRound);
        }
        final DataStreamLifecycle.Template lifecycle = DataStreamLifecycle.dataLifecycleBuilder()
            .downsamplingMethod(config.getSamplingMethod())
            .downsamplingRounds(rounds)
            .buildTemplate();
        logger.info("-> creating data stream with [{}] shards and [{}] replicas, downsampling rounds {}", shards, replicas, rounds);
        putTSDBIndexTemplate(DATA_STREAM + "*", "1986-01-08T23:40:53.384Z", "2022-01-08T23:40:53.384Z", lifecycle, shards, replicas);
        indexDocuments(DATA_STREAM, DOC_COUNT, "1990-09-09T18:00:00");
        // Remove the start/end time boundaries before the rollover, they're there just to ease testing, so the data stream
        // lifecycle doesn't have to wait for the end_time to lapse
        putTSDBIndexTemplate(DATA_STREAM + "*", null, null, lifecycle, shards, replicas);
        ensureGreen(DATA_STREAM);
        return getDataStreamBackingIndexNames(DATA_STREAM).get(0);
    }

    private void rollover() {
        safeGet(client().execute(RolloverAction.INSTANCE, new RolloverRequest(DATA_STREAM, null)));
    }

    /**
     * Parks the shard-level downsample tasks of {@code sourceIndex}, runs {@code start} to make the data stream lifecycle start
     * downsampling, and waits until all its tasks are parked. While they are, it downsamples {@code sourceIndex} without
     * disruption to produce the reference, and then parks the shard-level tasks again, to also park the ones that get reassigned
     * by the disruption the test applies next.
     */
    private void parkShardTasksAndProduceReference(String sourceIndex, int shards, DownsampleConfig config, Runnable start)
        throws Exception {
        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(sourceIndex));
        start.run();
        awaitParked(shards);
        DownsampleDisruptionPlugin.stopParking();
        downsampleReference(sourceIndex, config);
        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(sourceIndex));
    }

    /**
     * Downsamples {@code sourceIndex}, which the data stream lifecycle made read-only, into the reference index.
     */
    private void downsampleReference(String sourceIndex, DownsampleConfig config) {
        logger.info("-> downsampling [{}] into the reference index with [{}]", sourceIndex, config);
        assertAcked(
            client().execute(
                DownsampleAction.INSTANCE,
                new DownsampleAction.Request(TEST_REQUEST_TIMEOUT, sourceIndex, REFERENCE, TimeValue.timeValueMinutes(1), config)
            )
        );
    }

    /**
     * Waits until the data stream lifecycle replaced {@code replacedIndex} with {@code downsampleIndex} and deleted it, and asserts
     * that the downsample index is complete and identical to the reference.
     */
    private void assertDataStreamDownsampled(String downsampleIndex, String replacedIndex) throws Exception {
        logger.info("-> waiting for [{}] to replace [{}] in the data stream", downsampleIndex, replacedIndex);
        assertBusy(() -> {
            final List<String> backingIndices = getDataStreamBackingIndexNames(DATA_STREAM);
            assertThat(backingIndices, hasSize(2));
            assertThat(backingIndices.get(0), equalTo(downsampleIndex));
            assertFalse(indexExists(replacedIndex));
        }, 2, TimeUnit.MINUTES);

        final IndexMetadata metadata = clusterAdmin().prepareState(TEST_REQUEST_TIMEOUT)
            .get()
            .getState()
            .metadata()
            .getProject()
            .index(downsampleIndex);
        assertThat(IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(metadata.getSettings()), equalTo(IndexMetadata.DownsampleTaskStatus.SUCCESS));
        ensureGreen(downsampleIndex);
        assertSameDocuments(REFERENCE, downsampleIndex);
    }
}
