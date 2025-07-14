/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.scheduler.SchedulerEngine;
import org.elasticsearch.common.scheduler.TimeValueSchedule;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Strings;
import org.elasticsearch.core.TimeValue;

import java.io.Closeable;
import java.time.Clock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * This service schedules a periodic task/job.
 */
public abstract class AbstractPeriodicalService implements Closeable, SchedulerEngine.Listener {

    private final String jobName;
    private final Setting<TimeValue> pollIntervalSetting;
    private final Supplier<Boolean> canBeScheduled;
    private volatile TimeValue pollInterval;
    private final Logger logger;
    private final Settings settings;
    private final ClusterService clusterService;
    private LongSupplier nowSupplier;
    private final Clock clock;

    private SchedulerEngine.Job scheduledJob;
    private final SetOnce<SchedulerEngine> scheduler = new SetOnce<>();

    /**
     * The following stats are tracking how the service runs are performing time wise
     */
    private volatile Long lastRunStartedAt = null;
    private volatile Long timeBetweenStarts = null;
    private volatile Long lastRunDuration = null;

    /**
     * @param jobName the name of the task or job.
     * @param pollIntervalSetting how often it will run the task
     * @param settings settings to monitor the poll interval updates
     * @param clusterService cluster service so it can retrieve the cluster state
     * @param clock the clock based on which it will schedule the tasks
     * @param nowSupplier a separate supplier now supplier so we can influence time during tests
     * @param logger the logger of the concrete class
     * @param canBeScheduled the predicate that checks if this periodic task can be scheduled on this node.
     */
    public AbstractPeriodicalService(
        String jobName,
        Setting<TimeValue> pollIntervalSetting,
        Settings settings,
        ClusterService clusterService,
        Clock clock,
        LongSupplier nowSupplier,
        Logger logger,
        Supplier<Boolean> canBeScheduled
    ) {
        this.jobName = jobName;
        this.pollIntervalSetting = pollIntervalSetting;
        this.settings = settings;
        this.clusterService = clusterService;
        this.clock = clock;
        this.nowSupplier = nowSupplier;
        this.canBeScheduled = canBeScheduled;
        this.scheduledJob = null;
        this.pollInterval = pollIntervalSetting.get(settings);
        this.logger = logger;
    }

    /**
     * Initializer method to avoid the publication of a self reference in the constructor.
     */
    public void init() {
        clusterService.getClusterSettings().addSettingsUpdateConsumer(pollIntervalSetting, this::updatePollInterval);
    }

    @Override
    public void close() {
        SchedulerEngine engine = scheduler.get();
        if (engine != null) {
            engine.stop();
        }
    }

    /**
     * Runs the periodic task and tracks the running times
     */
    // default visibility for testing purposes
    protected void run(ClusterState state) {
        long startTime = nowSupplier.getAsLong();
        if (lastRunStartedAt != null) {
            timeBetweenStarts = startTime - lastRunStartedAt;
        }
        lastRunStartedAt = startTime;
        for (var projectId : state.metadata().projects().keySet()) {
            // We catch inside the loop to avoid one broken project preventing the service to run on other projects.
            try {
                run(state.projectState(projectId));
            } catch (Exception e) {
                logger.error(Strings.format("'%s' failed to run on project [%s]", jobName, projectId), e);
            }
        }
        lastRunDuration = nowSupplier.getAsLong() - lastRunStartedAt;
        logger.info("'{}' service ran for {}", jobName, TimeValue.timeValueMillis(lastRunDuration).toHumanReadableString(2));
    }

    protected abstract void run(ProjectState projectState);

    /**
     * @return the duration of the last run in millis or null if the service hasn't completed a run yet.
     */
    @Nullable
    public Long getLastRunDuration() {
        return lastRunDuration;
    }

    /**
     * @return the time passed between the start times of the last two consecutive runs or null if the service hasn't started twice yet.
     */
    @Nullable
    public Long getTimeBetweenStarts() {
        return timeBetweenStarts;
    }

    private void updatePollInterval(TimeValue newInterval) {
        this.pollInterval = newInterval;
        maybeScheduleJob();
    }

    private void cancelJob() {
        if (scheduler.get() != null) {
            scheduler.get().remove(jobName);
            scheduledJob = null;
        }
    }

    private boolean isClusterServiceStoppedOrClosed() {
        final Lifecycle.State state = clusterService.lifecycleState();
        return state == Lifecycle.State.STOPPED || state == Lifecycle.State.CLOSED;
    }

    protected void maybeScheduleJob() {

        if (canBeScheduled.get()) {
            // don't schedule the job if the node is shutting down
            if (isClusterServiceStoppedOrClosed()) {
                logger.info(
                    "Skipping scheduling a data stream lifecycle job due to the cluster lifecycle state being: [{}] ",
                    clusterService.lifecycleState()
                );
                return;
            }

            if (scheduler.get() == null) {
                scheduler.set(new SchedulerEngine(settings, clock));
                scheduler.get().register(this);
            }

            assert scheduler.get() != null : "scheduler should be available";
            scheduledJob = new SchedulerEngine.Job(jobName, new TimeValueSchedule(pollInterval));
            scheduler.get().add(scheduledJob);
        }
    }

    // visible for testing
    public void setNowSupplier(LongSupplier nowSupplier) {
        this.nowSupplier = nowSupplier;
    }

}
