/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Rounding;
import org.elasticsearch.common.scheduler.SchedulerEngine;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.xpack.core.ClientHelper;

import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This service will track and manage the incremental downsampling of data streams.
 * // TODO-ID: error tracking
 */
public class IncrementalDownsamplingService extends AbstractPeriodicalService {

    private static final Logger logger = LogManager.getLogger(IncrementalDownsamplingService.class);
    private static final String INCREMENTAL_DOWNSAMPLING_JOB_NAME = "incremental_downsampling";

    public static final String INCREMENTAL_DOWNSAMPLING_POLL_INTERVAL = "data_streams.incremental_downsampling.poll_interval";
    public static final Setting<TimeValue> INCREMENTAL_DOWNSAMPLING_POLL_INTERVAL_SETTING = Setting.timeSetting(
        INCREMENTAL_DOWNSAMPLING_POLL_INTERVAL,
        TimeValue.timeValueMinutes(5),
        TimeValue.timeValueSeconds(1),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    private static final List<DownsampleConfig> DOWNSAMPLE_CONFIGS = List.of(new DownsampleConfig(new DateHistogramInterval("5m")));

    static final DateHistogramInterval DOWNSAMPLING_INTERVAL = new DateHistogramInterval("5m");
    public static final DownsampleConfig DOWNSAMPLE_CONFIG = new DownsampleConfig(DOWNSAMPLING_INTERVAL);
    // TODO-ID: this should probably come from the config
    public static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    // TODO-ID: this should be persistent in the shard metadata
    private final Map<ProjectId, Map<String, DownsamplingProgressTracker>> downsamplingProgressTrackerPerProject = new HashMap<>();

    private final ClusterService clusterService;
    private final DataStreamDownsamplingService downsamplingService;
    private final PocHelper pocHelper;
    private final Clock clock;
    private final ConcurrentHashMap<String, Boolean> inProgress = new ConcurrentHashMap<>();

    public IncrementalDownsamplingService(
        Settings settings,
        ClusterService clusterService,
        IndicesService indicesService,
        Clock clock,
        Client client
    ) {
        super(
            INCREMENTAL_DOWNSAMPLING_JOB_NAME,
            INCREMENTAL_DOWNSAMPLING_POLL_INTERVAL_SETTING,
            settings,
            clusterService,
            clock,
            clock::millis,
            logger,
            () -> true
        );
        this.clusterService = clusterService;
        this.clock = clock;
        Client downsamplingClient = new OriginSettingClient(client, ClientHelper.ROLLUP_ORIGIN);
        this.pocHelper = new PocHelper(indicesService, downsamplingClient);
        this.downsamplingService = new DataStreamDownsamplingService(indicesService, pocHelper, clock, downsamplingClient);
    }

    /**
     * Initializer method to avoid the publication of a self-reference in the constructor.
     */
    public void init() {
        super.init();
        maybeScheduleJob();
    }

    @Override
    public void triggered(SchedulerEngine.Event event) {
        if (event.jobName().equals(INCREMENTAL_DOWNSAMPLING_JOB_NAME)) {
            logger.info(
                "Incremental downsampling job triggered: {}, {}, {}",
                event.jobName(),
                event.scheduledTime(),
                event.triggeredTime()
            );
            run(clusterService.state());
        }
    }

    @Override
    protected void run(ProjectState projectState) {
        final var project = projectState.metadata();
        final Map<String, DownsamplingProgressTracker> downsamplingCheckpoints = downsamplingProgressTrackerPerProject.computeIfAbsent(
            project.id(),
            ignored -> new HashMap<>()
        );
        // TODO-ID: what about timezoned buckets?
        long now = clock.millis();
        for (int i = 0; i < DOWNSAMPLE_CONFIGS.size(); i++) {
            var currentDownsamplingLayer = DOWNSAMPLE_CONFIGS.get(i);
            for (DataStream dataStream : project.dataStreams().values()) {
                // Can we count that the 30-minute delay is enough or should we refresh
                if (dataStream.hasIncrementalDownsamplingEnabled()) {
                    DownsamplingProgressTracker downsamplingProgressTracker = downsamplingCheckpoints.computeIfAbsent(
                        dataStream.getName(),
                        ignored -> new DownsamplingProgressTracker(dataStream.getDownsamplingProgress(currentDownsamplingLayer.getInterval().toString()).startTime(), currentDownsamplingLayer.getInterval())
                    );
                    if (inProgress.putIfAbsent(dataStream.getName(), true) != null) {
                        logger.info("{} is already being downsampled.", dataStream.getName());
                        continue;
                    }
                    SubscribableListener.<Void>newForked(
                        l -> pocHelper.maybeCreateDownsampleTemplate(project, dataStream.getName(), dataStream.getWriteIndex(), l)
                    )
                        .<Void>andThen(
                            (l, layerExists) -> downsamplingService.performDownsampling(
                                project,
                                dataStream,
                                currentDownsamplingLayer,
                                downsamplingProgressTracker,
                                l
                            )
                        )
                        .addListener(new ActionListener<>() {
                            @Override
                            public void onResponse(Void ignored) {
                                inProgress.remove(dataStream.getName());
                            }

                            @Override
                            public void onFailure(Exception e) {
                                logger.error("failed to downsample data stream [{}], {}", dataStream.getName(), e);
                                inProgress.remove(dataStream.getName());
                            }
                        });
                }
            }
        }
    }
}
