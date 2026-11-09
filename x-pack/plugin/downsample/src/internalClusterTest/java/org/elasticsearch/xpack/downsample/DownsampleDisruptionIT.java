/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.admin.indices.flush.FlushAction;
import org.elasticsearch.action.admin.indices.refresh.RefreshAction;
import org.elasticsearch.action.admin.indices.settings.put.TransportUpdateSettingsAction;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.TransportBulkAction;
import org.elasticsearch.action.downsample.DownsampleAction;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.disruption.NetworkDisruption;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.xpack.core.downsample.DownsampleShardTask;
import org.junit.After;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * Disrupts a downsample operation, started through the downsample API, while it is in flight. ILM and the data stream lifecycle
 * both drive downsampling through this API, so these are the recovery paths they rely on.
 * <p>
 * Every test parks the operation at a chosen point with {@link DownsampleDisruptionPlugin}, applies a disruption, lets the
 * operation continue and then asserts that the downsample index is complete and identical to the result of a downsample
 * of the same source that ran without disruption. The source index has a random number of shards and replicas, so the
 * operation is made of several shard-level persistent tasks spread over the data nodes.
 * <p>
 * Some disruptions make the downsample request fail. The API is designed to be retried in that case: a retry picks up the
 * downsample index left behind by the failed attempt. The tests that expect a failure retry the request, the others require
 * the original request to succeed.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0, numClientNodes = 0)
public class DownsampleDisruptionIT extends DownsamplingIntegTestCase {

    private static final Logger logger = LogManager.getLogger(DownsampleDisruptionIT.class);

    private static final String SOURCE = "source";
    private static final String TARGET = "target";
    private static final int DATA_NODES = 3;
    private static final int DOC_COUNT = 10_000;
    private static final TimeValue WAIT_TIMEOUT = TimeValue.timeValueMinutes(2);

    private DownsampleConfig config;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(DownsampleDisruptionPlugin.class);
        plugins.add(MockTransportService.TestPlugin.class);
        return plugins;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal, otherSettings))
            // A shard-level downsample task is not reassigned when its source shard relocates, so the task fails. Rebalancing can
            // relocate source shards at any time, for example when the downsample index is created, so it is disabled to have each
            // test exercise only the disruption it applies. testSourceShardRelocation covers the relocation of source shards.
            .put(EnableAllocationDecider.CLUSTER_ROUTING_REBALANCE_ENABLE_SETTING.getKey(), "none")
            .build();
    }

    @After
    public void resetDisruptionPlugin() {
        DownsampleDisruptionPlugin.reset();
    }

    /**
     * Restarts a node that runs a shard-level downsample task.
     * Expected: its tasks are reassigned once their source shards are available again, and the original request succeeds.
     */
    public void testNodeRunningShardTaskRestarts() throws Exception {
        final String coordinator = startCluster(1);
        final int shards = between(1, 3);
        createReadOnlyTimeSeriesIndex(SOURCE, shards, between(0, 1), DOC_COUNT);

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
        awaitParked(shards);

        final String victim = randomFrom(DownsampleDisruptionPlugin.parkedOnNodes());
        logger.info("-> restarting node [{}] running a shard downsample task", victim);
        internalCluster().restartNode(victim);
        ensureStableCluster(internalCluster().size());
        // Every task, including the ones that ran on the restarted node, must be running again before we let them continue.
        awaitParked(shards);

        DownsampleDisruptionPlugin.release();
        assertDownsampleSucceeds(downsample);
        assertDownsampleCompleted(coordinator, TARGET);
    }

    /**
     * Stops a node that runs a shard-level downsample task.
     * Expected: its tasks are reassigned to the nodes holding the promoted replicas, and the original request succeeds.
     */
    public void testNodeRunningShardTaskLeaves() throws Exception {
        final String coordinator = startCluster(1);
        final int shards = between(1, 3);
        createReadOnlyTimeSeriesIndex(SOURCE, shards, 1, DOC_COUNT);

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
        awaitParked(shards);

        final String victim = randomFrom(DownsampleDisruptionPlugin.parkedOnNodes());
        // The downsample index has no replicas while it is being built, so losing a node that holds one of its shards loses data.
        // This test covers the loss of the source shards only, so the downsample index shards are moved off the node first.
        // testNodeHoldingTargetShardLeaves covers the loss of a downsample index shard.
        moveShardsOff(TARGET, victim, true);

        logger.info("-> stopping node [{}] running a shard downsample task", victim);
        internalCluster().stopNode(victim);
        ensureStableCluster(internalCluster().size());
        awaitParked(shards);
        assertThat(DownsampleDisruptionPlugin.parkedOnNodes(), not(hasItem(victim)));

        DownsampleDisruptionPlugin.release();
        assertDownsampleSucceeds(downsample);
        assertDownsampleCompleted(coordinator, TARGET);
    }

    /**
     * Stops the node holding the only copy of a downsample index shard, which has no replicas while it is being built.
     * Expected: the original request and a retry fail while the node is down, and a retry succeeds once the node is back.
     */
    public void testNodeHoldingTargetShardLeaves() throws Exception {
        final String coordinator = startCluster(1);
        final int shards = between(1, 3);
        // The source index has a replica, so that the loss of the node does not also make the source shards unavailable
        createReadOnlyTimeSeriesIndex(SOURCE, shards, 1, DOC_COUNT);

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
        awaitParked(shards);

        // Documents are routed by their dimensions, so some shards can be empty. The downsample of an empty shard writes nothing to
        // the downsample index, so the loss of that downsample index shard would not affect the operation.
        final String victim = randomFrom(nodesHoldingTargetShardsWithDocuments());
        logger.info("-> stopping node [{}] holding a shard of the downsample index", victim);
        internalCluster().restartNode(victim, new InternalTestCluster.RestartCallback() {
            @Override
            public Settings onNodeStopped(String nodeName) {
                // The stopped node is no longer counted in the size of the cluster
                ensureStableCluster(internalCluster().size(), coordinator);
                // Let the shard-level tasks, including the ones reassigned from the stopped node, write to the downsample index
                DownsampleDisruptionPlugin.release();

                final Exception originalFailure = expectThrows(Exception.class, () -> downsample.actionGet(WAIT_TIMEOUT));
                logger.info("-> the original downsample request failed while node [{}] is down", nodeName, originalFailure);

                final Exception retryFailure = expectThrows(
                    Exception.class,
                    () -> startDownsample(coordinator, TARGET).actionGet(WAIT_TIMEOUT)
                );
                logger.info("-> the retried downsample request failed while node [{}] is down", nodeName, retryFailure);
                assertThat(
                    IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(indexMetadata(TARGET).getSettings()),
                    equalTo(IndexMetadata.DownsampleTaskStatus.STARTED)
                );
                return Settings.EMPTY;
            }
        });
        ensureStableCluster(internalCluster().size());
        // The node brings back the only copy of the downsample index shard
        ensureGreen(TARGET);

        assertDownsampleSucceeds(startDownsample(coordinator, TARGET));
        assertDownsampleCompleted(coordinator, TARGET);
    }

    /**
     * Isolates a node that runs a shard-level downsample task until its tasks are reassigned, and then lets it rejoin.
     * Expected: the stale tasks of the rejoined node give up, and the original request succeeds.
     */
    public void testNodeRunningShardTaskIsIsolated() throws Exception {
        final String coordinator = startCluster(1);
        final int shards = between(1, 3);
        createReadOnlyTimeSeriesIndex(SOURCE, shards, 1, DOC_COUNT);

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
        awaitParked(shards);

        final String victim = randomFrom(DownsampleDisruptionPlugin.parkedOnNodes());
        final long tasksOnVictim = DownsampleDisruptionPlugin.parkedOnNodes().stream().filter(victim::equals).count();
        final Set<String> otherNodes = new HashSet<>(Arrays.asList(internalCluster().getNodeNames()));
        otherNodes.remove(victim);
        final NetworkDisruption partition = new NetworkDisruption(
            new NetworkDisruption.TwoPartitions(Set.of(victim), otherNodes),
            NetworkDisruption.DISCONNECT
        );
        internalCluster().setDisruptionScheme(partition);

        logger.info("-> isolating node [{}] running a shard downsample task", victim);
        partition.startDisrupting();
        ensureStableCluster(otherNodes.size(), coordinator);
        // The tasks of the isolated node are reassigned to the nodes holding the promoted replicas
        assertBusy(
            () -> assertThat(
                DownsampleDisruptionPlugin.parkedOnNodes().stream().filter(node -> victim.equals(node) == false).count(),
                equalTo((long) shards)
            ),
            30,
            TimeUnit.SECONDS
        );

        logger.info("-> healing the partition");
        partition.stopDisrupting();
        ensureStableCluster(otherNodes.size() + 1);
        assertThat(DownsampleDisruptionPlugin.parkedOnNodes().stream().filter(victim::equals).count(), equalTo(tasksOnVictim));

        // This also releases the stale tasks of the isolated node. They are no longer assigned to it and must give up.
        DownsampleDisruptionPlugin.release();
        assertDownsampleSucceeds(downsample);
        assertDownsampleCompleted(coordinator, TARGET);
    }

    private enum MasterFailoverPoint {
        /** The shard-level downsample tasks are running. */
        SHARD_TASKS,
        /** All shard-level tasks completed, the master is making the downsample index read-only. */
        UPDATE_TARGET_SETTINGS,
        /** The downsample index is read-only, the master is refreshing it before marking it as successfully downsampled. */
        REFRESH_TARGET,
        /** The downsample index is marked as successfully downsampled, the master is flushing it before responding. */
        FLUSH_TARGET
    }

    /**
     * Fails over the master at each step of the downsample operation it coordinates.
     * Expected: the request is retried on the new master, which completes the operation, and the original request succeeds.
     */
    public void testMasterFailover() throws Exception {
        final String coordinator = startCluster(3);
        final int shards = between(1, 3);
        createReadOnlyTimeSeriesIndex(SOURCE, shards, between(0, 1), DOC_COUNT);

        final MasterFailoverPoint failoverPoint = randomFrom(MasterFailoverPoint.values());
        logger.info("-> failing over the master at [{}]", failoverPoint);
        final int parked = switch (failoverPoint) {
            case SHARD_TASKS -> {
                DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
                yield shards;
            }
            case UPDATE_TARGET_SETTINGS -> parkOnce(TransportUpdateSettingsAction.TYPE.name());
            case REFRESH_TARGET -> parkOnce(RefreshAction.NAME);
            case FLUSH_TARGET -> parkOnce(FlushAction.NAME);
        };
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
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
        assertDownsampleSucceeds(downsample);
        assertDownsampleCompleted(coordinator, TARGET);
    }

    /**
     * Relocates the source shards away from a node that runs a shard-level downsample task.
     * Expected: the task is not reassigned, so it fails, and a retry of the request succeeds.
     */
    public void testSourceShardRelocation() throws Exception {
        final String coordinator = startCluster(1);
        final int shards = between(1, 3);
        createReadOnlyTimeSeriesIndex(SOURCE, shards, 0, DOC_COUNT);

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
        awaitParked(shards);

        final String node = randomFrom(DownsampleDisruptionPlugin.parkedOnNodes());
        logger.info("-> relocating the source shards off node [{}] running a shard downsample task", node);
        moveShardsOff(SOURCE, node, true);

        DownsampleDisruptionPlugin.release();
        assertDownsampleSucceedsAfterRetries(downsample, coordinator, TARGET);
        assertDownsampleCompleted(coordinator, TARGET);
    }

    /**
     * Relocates the downsample index shards, before or while the shard-level tasks write to them.
     * Expected: the original request succeeds.
     */
    public void testTargetShardRelocation() throws Exception {
        final String coordinator = startCluster(1);
        final int shards = between(1, 3);
        createReadOnlyTimeSeriesIndex(SOURCE, shards, between(0, 1), DOC_COUNT);

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
        awaitParked(shards);

        final String node = randomFrom(nodesHoldingShards(TARGET));
        final boolean overlapWithIndexing = randomBoolean();
        logger.info("-> relocating the downsample index shards off node [{}], overlapping with indexing [{}]", node, overlapWithIndexing);
        moveShardsOff(TARGET, node, overlapWithIndexing == false);

        DownsampleDisruptionPlugin.release();
        assertDownsampleSucceeds(downsample);
        assertDownsampleCompleted(coordinator, TARGET);
    }

    private enum FailurePoint {
        PERSISTENT_TASK_START,
        BULK_INDEXING,
        UPDATE_TARGET_SETTINGS,
        REFRESH_TARGET,
        FLUSH_TARGET
    }

    /**
     * Fails one of the actions the downsample operation is made of.
     * Expected: a failed flush is ignored, any other failure fails the request, and a retry succeeds.
     */
    public void testStepFailure() throws Exception {
        final String coordinator = startCluster(1);
        createReadOnlyTimeSeriesIndex(SOURCE, between(1, 3), between(0, 1), DOC_COUNT);

        final FailurePoint failurePoint = randomFrom(FailurePoint.values());
        logger.info("-> failing [{}]", failurePoint);
        final BiPredicate<String, ActionRequest> matcher = switch (failurePoint) {
            case PERSISTENT_TASK_START -> DownsampleDisruptionPlugin.persistentTaskStart();
            case BULK_INDEXING -> DownsampleDisruptionPlugin.actionOnIndex(TransportBulkAction.NAME, TARGET);
            case UPDATE_TARGET_SETTINGS -> DownsampleDisruptionPlugin.actionOnIndex(TransportUpdateSettingsAction.TYPE.name(), TARGET);
            case REFRESH_TARGET -> DownsampleDisruptionPlugin.actionOnIndex(RefreshAction.NAME, TARGET);
            case FLUSH_TARGET -> DownsampleDisruptionPlugin.actionOnIndex(FlushAction.NAME, TARGET);
        };
        DownsampleDisruptionPlugin.failNext(1, matcher);

        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);
        switch (failurePoint) {
            // The downsample index is complete and marked as such before it is flushed, so a failed flush is ignored
            case FLUSH_TARGET -> assertDownsampleSucceeds(downsample);
            // A failed bulk request aborts the shard-level task, which may either fail or be retried
            case BULK_INDEXING -> assertDownsampleSucceedsAfterRetries(downsample, coordinator, TARGET);
            // The failure of a step the master coordinates is reported to the caller, which is expected to retry
            case PERSISTENT_TASK_START, UPDATE_TARGET_SETTINGS, REFRESH_TARGET -> {
                expectThrows(Exception.class, () -> downsample.actionGet(WAIT_TIMEOUT));
                assertDownsampleSucceeds(startDownsample(coordinator, TARGET));
            }
        }
        assertThat("the failure was not injected", DownsampleDisruptionPlugin.remainingFailures(), equalTo(0));
        assertDownsampleCompleted(coordinator, TARGET);
    }

    /**
     * Sends a second request for the same downsample index while the first one is in progress.
     * Expected: the first request succeeds. The second one succeeds if it finds the first one's shard-level tasks, otherwise it starts new
     * ones that fail once the first request makes the downsample index read-only.
     */
    public void testSecondRequestWhileFirstInProgress() throws Exception {
        final String coordinator = startCluster(1);
        final int shards = between(1, 3);
        createReadOnlyTimeSeriesIndex(SOURCE, shards, between(0, 1), DOC_COUNT);

        DownsampleDisruptionPlugin.parkAll(DownsampleDisruptionPlugin.shardTask(SOURCE));
        final ActionFuture<AcknowledgedResponse> first = startDownsample(coordinator, TARGET);
        awaitParked(shards);

        final ActionFuture<AcknowledgedResponse> second = startDownsample(
            randomFrom(internalCluster().getNodeNames()),
            TARGET,
            WAIT_TIMEOUT
        );
        DownsampleDisruptionPlugin.release();
        assertDownsampleSucceeds(first);
        try {
            assertTrue(second.actionGet(WAIT_TIMEOUT).isAcknowledged());
            logger.info("-> the second downsample request succeeded");
        } catch (Exception e) {
            logger.info("-> the second downsample request failed", e);
            assertThat(e.getMessage(), containsString("] failed"));
        }
        assertDownsampleCompleted(coordinator, TARGET);
    }

    /**
     * Downsamples the same source into several downsample indices concurrently.
     * Expected: every request succeeds and every downsample index is complete.
     */
    public void testConcurrentRequestsForDifferentTargets() throws Exception {
        final String coordinator = startCluster(1);
        createReadOnlyTimeSeriesIndex(SOURCE, between(1, 3), between(0, 1), DOC_COUNT);

        final List<String> targets = new ArrayList<>();
        final List<ActionFuture<AcknowledgedResponse>> requests = new ArrayList<>();
        for (int i = between(3, 6); i > 0; i--) {
            final String target = TARGET + "-" + i;
            targets.add(target);
            requests.add(startDownsample(randomFrom(internalCluster().getNodeNames()), target, WAIT_TIMEOUT));
        }
        for (ActionFuture<AcknowledgedResponse> request : requests) {
            assertTrue(request.actionGet(WAIT_TIMEOUT).isAcknowledged());
        }
        assertDownsampleCompleted(coordinator, targets.get(0));
        for (String target : targets.subList(1, targets.size())) {
            assertThat(
                IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(indexMetadata(target).getSettings()),
                equalTo(IndexMetadata.DownsampleTaskStatus.SUCCESS)
            );
            assertSameDocuments(targets.get(0), target);
        }
    }

    /**
     * Removes the shard-level persistent task after its first bulk request landed, and retries the request.
     * Expected: the original request fails, and the retry completes the operation, skipping the already indexed buckets as version
     * conflicts.
     */
    public void testInterruptedRunIsRetried() throws Exception {
        final String coordinator = startCluster(1);
        // One bucket per document, so the shard-level task sends several bulk requests. Either every bulk request holds other time
        // series, or all of them hold the same one.
        final boolean singleSeries = randomBoolean();
        createReadOnlyTimeSeriesIndex(
            SOURCE,
            1,
            between(0, 1),
            singleSeries ? 15_000 : 20_000,
            singleSeries ? TimeSeriesLayout.ONE_SERIES_ONE_DOCUMENT_PER_HOUR : TimeSeriesLayout.ONE_SERIES_PER_DOCUMENT
        );

        final long indexedByInterruptedRun = interruptAfterFirstBulk(coordinator);

        assertTrue(startDownsample(coordinator, TARGET, WAIT_TIMEOUT).actionGet(WAIT_TIMEOUT).isAcknowledged());
        final Map<String, DocSnapshot> docs = assertDownsampleCompleted(coordinator, TARGET);
        assertThat((long) docs.size(), greaterThan(indexedByInterruptedRun));
        assertNoDocumentRewritten(docs);
        assertThat(versionConflicts(TARGET), equalTo(indexedByInterruptedRun));
    }

    /**
     * Like {@link #testInterruptedRunIsRetried()}, muted until resuming from the last time series is reinstated, see
     * https://github.com/elastic/elasticsearch/issues/164609.
     * Expected: the retried task only recomputes the buckets of the last time series the interrupted run indexed.
     */
    public void testInterruptedRunResumesFromLastTimeSeries() throws Exception {
        final String coordinator = startCluster(1);
        createReadOnlyTimeSeriesIndex(SOURCE, 1, between(0, 1), 20_000, TimeSeriesLayout.ONE_SERIES_PER_DOCUMENT);

        final long indexedByInterruptedRun = interruptAfterFirstBulk(coordinator);

        assertTrue(startDownsample(coordinator, TARGET, WAIT_TIMEOUT).actionGet(WAIT_TIMEOUT).isAcknowledged());
        final Map<String, DocSnapshot> docs = assertDownsampleCompleted(coordinator, TARGET);
        assertThat((long) docs.size(), greaterThan(indexedByInterruptedRun));
        assertNoDocumentRewritten(docs);
        assertThat(versionConflicts(TARGET), allOf(greaterThan(0L), lessThan(indexedByInterruptedRun)));
    }

    /**
     * Fails the second bulk request of a shard-level task after every later one landed, leaving a gap in the downsample index.
     * Expected: the original request fails, and the retry fills the gap without indexing any document twice.
     */
    public void testFailedNonLastBulkIsRetried() throws Exception {
        final String coordinator = startCluster(1);
        // A time series per document and a bucket per time series, so consecutive bulk requests hold different time series and the
        // shard-level task has to send at least three of them
        createReadOnlyTimeSeriesIndex(SOURCE, 1, between(0, 1), 30_000, TimeSeriesLayout.ONE_SERIES_PER_DOCUMENT);
        final long buckets = countDocuments(SOURCE);

        final DownsampleDisruptionPlugin.OrdinalMatcher bulks = DownsampleDisruptionPlugin.ordinal(
            DownsampleDisruptionPlugin.actionOnIndex(TransportBulkAction.NAME, TARGET),
            ordinal -> ordinal == 1
        );
        DownsampleDisruptionPlugin.parkAll(bulks);
        final ActionFuture<AcknowledgedResponse> downsample = startDownsample(coordinator, TARGET);

        // Wait until every bulk request but the parked one landed, which leaves the shard-level task waiting for the parked one
        assertBusy(() -> {
            final List<ActionRequest> parked = DownsampleDisruptionPlugin.parkedRequests();
            assertThat(parked, hasSize(1));
            assertThat(countDocuments(TARGET), equalTo(buckets - ((BulkRequest) parked.get(0)).numberOfActions()));
        }, 60, TimeUnit.SECONDS);
        // At least one bulk request was sent after the parked one, so the parked one is not the last one
        assertThat(bulks.matched(), greaterThanOrEqualTo(3));

        DownsampleDisruptionPlugin.failParked();
        final Exception failure = expectThrows(Exception.class, () -> downsample.actionGet(WAIT_TIMEOUT));
        assertThat(failure.getMessage(), containsString("] failed"));
        awaitNoShardTasks();
        assertThat(
            IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(indexMetadata(TARGET).getSettings()),
            equalTo(IndexMetadata.DownsampleTaskStatus.STARTED)
        );

        assertTrue(startDownsample(coordinator, TARGET, WAIT_TIMEOUT).actionGet(WAIT_TIMEOUT).isAcknowledged());
        final Map<String, DocSnapshot> docs = assertDownsampleCompleted(coordinator, TARGET);
        assertThat((long) docs.size(), equalTo(buckets));
        assertNoDocumentRewritten(docs);
    }

    /**
     * Starts downsampling {@link #SOURCE}, which must have a single shard, lets only the first bulk request of its shard-level task
     * through and removes the persistent task while the next bulk request is parked. This leaves a partially populated downsample
     * index in the {@code STARTED} status, as if the task had been interrupted.
     *
     * @return the number of documents the interrupted run indexed
     */
    private long interruptAfterFirstBulk(String coordinator) throws Exception {
        DownsampleDisruptionPlugin.parkAll(
            DownsampleDisruptionPlugin.ordinal(
                DownsampleDisruptionPlugin.actionOnIndex(TransportBulkAction.NAME, TARGET),
                ordinal -> ordinal > 0
            )
        );
        // The tiny wait timeout makes the request fail right after it created the downsample index and the shard-level task, so it
        // does not complete the downsample index once the task is removed
        expectThrows(Exception.class, () -> startDownsample(coordinator, TARGET, TimeValue.timeValueMillis(1)).actionGet(WAIT_TIMEOUT));

        // Wait until the first bulk request landed and the next one is parked
        assertBusy(() -> {
            assertThat(DownsampleDisruptionPlugin.parkedRequests(), not(empty()));
            assertThat(countDocuments(TARGET), greaterThan(0L));
        }, 60, TimeUnit.SECONDS);

        final PersistentTasksService persistentTasksService = internalCluster().getInstance(PersistentTasksService.class);
        final List<String> taskIds = shardTasks().stream().map(PersistentTasksCustomMetadata.PersistentTask::getId).toList();
        assertThat(taskIds, hasSize(1));
        logger.info("-> removing the persistent task {} while its next bulk request is in flight", taskIds);
        for (String taskId : taskIds) {
            safeAwait(
                (ActionListener<PersistentTasksCustomMetadata.PersistentTask<?>> listener) -> persistentTasksService
                    .sendProjectRemoveRequest(ProjectId.DEFAULT, taskId, TEST_REQUEST_TIMEOUT, listener)
            );
        }

        // Fail the parked bulk requests so the task that lost its persistent task stops without indexing anything else
        DownsampleDisruptionPlugin.failParked();
        awaitNoShardTasks();
        assertThat(
            IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(indexMetadata(TARGET).getSettings()),
            equalTo(IndexMetadata.DownsampleTaskStatus.STARTED)
        );
        final long indexed = countDocuments(TARGET);
        assertThat(indexed, greaterThan(0L));
        logger.info("-> the interrupted run indexed [{}] documents", indexed);
        return indexed;
    }

    private static List<PersistentTasksCustomMetadata.PersistentTask<?>> shardTasks() {
        final PersistentTasksCustomMetadata tasks = PersistentTasksCustomMetadata.get(
            clusterAdmin().prepareState(TEST_REQUEST_TIMEOUT).get().getState().metadata().getProject()
        );
        return tasks == null ? List.of() : List.copyOf(tasks.findTasks(DownsampleShardTask.TASK_NAME, task -> true));
    }

    /**
     * Waits until no downsample persistent task exists and no shard-level task runs on any node.
     */
    private static void awaitNoShardTasks() throws Exception {
        assertBusy(() -> {
            assertThat(shardTasks(), empty());
            assertThat(clusterAdmin().prepareListTasks().setActions(DownsampleShardTask.TASK_NAME + "[c]").get().getTasks(), empty());
        });
    }

    /**
     * Starts a master-only node per {@code masterNodes}, {@link #DATA_NODES} data nodes and a coordinating-only node, which
     * sends the downsample requests so that it is never the node a test disrupts.
     *
     * @return the name of the coordinating-only node
     */
    private String startCluster(int masterNodes) {
        internalCluster().startMasterOnlyNodes(masterNodes);
        internalCluster().startDataOnlyNodes(DATA_NODES);
        final String coordinator = internalCluster().startCoordinatingOnlyNode(Settings.EMPTY);
        ensureStableCluster(masterNodes + DATA_NODES + 1);
        config = new DownsampleConfig(new DateHistogramInterval("1h"), randomSamplingMethod());
        return coordinator;
    }

    private ActionFuture<AcknowledgedResponse> startDownsample(String coordinator, String target) {
        return startDownsample(coordinator, target, WAIT_TIMEOUT);
    }

    /**
     * Sends a downsample request through {@code node}. The request waits up to {@code waitTimeout} for the shard-level tasks.
     */
    private ActionFuture<AcknowledgedResponse> startDownsample(String node, String target, TimeValue waitTimeout) {
        logger.info("-> downsampling [{}] to [{}] with [{}] through node [{}]", SOURCE, target, config, node);
        return client(node).execute(
            DownsampleAction.INSTANCE,
            new DownsampleAction.Request(TEST_REQUEST_TIMEOUT, SOURCE, target, waitTimeout, config)
        );
    }

    private static int parkOnce(String action) {
        DownsampleDisruptionPlugin.park(1, DownsampleDisruptionPlugin.actionOnIndex(action, TARGET));
        return 1;
    }

    /**
     * Excludes {@code node} from the allocation of {@code index}, optionally waiting until its shards have moved off it.
     */
    private void moveShardsOff(String index, String node, boolean waitForRelocation) throws Exception {
        updateIndexSettings(Settings.builder().put(IndexMetadata.INDEX_ROUTING_EXCLUDE_GROUP_PREFIX + "._name", node), index);
        if (waitForRelocation) {
            assertBusy(() -> assertThat(nodesHoldingShards(index), not(hasItem(node))), 60, TimeUnit.SECONDS);
            ensureGreen(index);
        }
    }

    private static List<String> nodesHoldingShards(String index) {
        final ClusterState state = clusterAdmin().prepareState(TEST_REQUEST_TIMEOUT).get().getState();
        return state.routingTable()
            .allShards(index)
            .stream()
            .filter(ShardRouting::assignedToNode)
            .map(shard -> state.nodes().get(shard.currentNodeId()).getName())
            .toList();
    }

    /**
     * @return the nodes holding a shard of the downsample index that the downsample operation writes documents to. The downsample
     * index has as many shards as the source index and routes documents the same way, so its shard with a given id receives the
     * downsampled documents of the source shard with the same id.
     */
    private static List<String> nodesHoldingTargetShardsWithDocuments() {
        final Set<Integer> shardsWithDocuments = new HashSet<>();
        for (ShardStats shardStats : indicesAdmin().prepareStats(SOURCE).setDocs(true).get().getShards()) {
            if (shardStats.getShardRouting().primary() && shardStats.getStats().getDocs().getCount() > 0) {
                shardsWithDocuments.add(shardStats.getShardRouting().id());
            }
        }
        final ClusterState state = clusterAdmin().prepareState(TEST_REQUEST_TIMEOUT).get().getState();
        final List<String> nodes = state.routingTable()
            .allShards(TARGET)
            .stream()
            .filter(shard -> shard.assignedToNode() && shardsWithDocuments.contains(shard.id()))
            .map(shard -> state.nodes().get(shard.currentNodeId()).getName())
            .toList();
        assertThat(nodes, not(empty()));
        return nodes;
    }

    private static void assertDownsampleSucceeds(ActionFuture<AcknowledgedResponse> downsample) {
        assertTrue(safeGet(downsample).isAcknowledged());
    }

    /**
     * Waits for the original downsample request and, if it failed, retries it until it succeeds.
     */
    private void assertDownsampleSucceedsAfterRetries(ActionFuture<AcknowledgedResponse> downsample, String coordinator, String target)
        throws Exception {
        try {
            assertTrue(downsample.actionGet(WAIT_TIMEOUT).isAcknowledged());
            logger.info("-> the original downsample request succeeded");
            return;
        } catch (Exception e) {
            logger.info("-> the original downsample request failed, retrying", e);
        }
        assertBusy(() -> {
            try {
                assertTrue(startDownsample(coordinator, target).actionGet(WAIT_TIMEOUT).isAcknowledged());
            } catch (Exception e) {
                throw new AssertionError("downsample retry failed", e);
            }
        }, 2, TimeUnit.MINUTES);
    }

    /**
     * Asserts that the downsample index is complete, that no shard-level task is left behind and that the downsample index is
     * identical to the one a downsample of the same source produces without disruption.
     *
     * @return every document of the downsample index keyed by {@code _id}
     */
    private Map<String, DocSnapshot> assertDownsampleCompleted(String coordinator, String target) throws Exception {
        final IndexMetadata sourceMetadata = indexMetadata(SOURCE);
        final IndexMetadata targetMetadata = indexMetadata(target);
        assertThat(
            IndexMetadata.INDEX_DOWNSAMPLE_STATUS.get(targetMetadata.getSettings()),
            equalTo(IndexMetadata.DownsampleTaskStatus.SUCCESS)
        );
        assertThat(targetMetadata.getSettings().getAsBoolean(IndexMetadata.SETTING_BLOCKS_WRITE, false), equalTo(true));
        assertThat(targetMetadata.getNumberOfReplicas(), equalTo(sourceMetadata.getNumberOfReplicas()));
        assertThat(targetMetadata.getNumberOfShards(), equalTo(sourceMetadata.getNumberOfShards()));
        ensureGreen(target);
        assertBusy(() -> assertThat(shardTasks(), empty()));

        DownsampleDisruptionPlugin.reset();
        final String reference = "reference-" + target;
        assertTrue(startDownsample(coordinator, reference).actionGet(WAIT_TIMEOUT).isAcknowledged());
        return assertSameDocuments(reference, target);
    }

    private static IndexMetadata indexMetadata(String index) {
        return clusterAdmin().prepareState(TEST_REQUEST_TIMEOUT).get().getState().metadata().getProject().index(index);
    }
}
