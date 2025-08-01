/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.Rounding;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.shard.ShardId;

import java.time.Instant;

/**
 * Captures the progress of incremental downsampling specific to a layer.
 */
public class DownsamplingShardMetadata {

    private static final Logger logger = LogManager.getLogger(DownsamplingShardMetadata.class);
    private final long gracePeriod;

    private final TimeValue interval;
    // This can be either the start time of the time series index or the configuration time of this layer.
    private final long startBoundMillis;
    private final Rounding rounding;
    // This is the most recent timestamp that has successfully downsampled data
    private volatile Long lastSuccessfulDownsampled = null;
    // TODO-ID: An ordered set would be useful here to keep track of the time ranges that need to be downsampled.

    public DownsamplingShardMetadata(ShardId shardId, Rounding rounding, long gracePeriod, TimeValue interval, long startBoundMillis) {
        this.gracePeriod = gracePeriod;
        this.interval = interval;
        this.startBoundMillis = startBoundMillis;
        this.rounding = rounding;
        logger.info(
            "Initialising layer {} downsampling progress tracker for shard {} first task starting from {}.",
            interval.getStringRep(),
            shardId,
            Instant.ofEpochMilli(startBoundMillis)
        );
    }

    DownsamplingProgressTracker.TimeRange getNextTimeRangeToDownsample(Instant now, Instant endBound) {
        long startTime = lastSuccessfulDownsampled == null ? startBoundMillis : lastSuccessfulDownsampled;
        long temp = rounding.prepareForUnknown().round(startTime + interval.millis());
        long endTimeMillis = endBound == null ? temp : Math.min(temp, endBound.toEpochMilli());
        if (now.toEpochMilli() - gracePeriod > endTimeMillis) {
            return new DownsamplingProgressTracker.TimeRange(startTime, endTimeMillis);
        }
        // TODO-ID: here we could potentially return also time ranges that need to be updated when we add support for that.
        return null;
    }

    // We need to take index end time into consideration.
    void setLastSuccessfulDownsampled(long lastSuccessfulDownsampled) {
        this.lastSuccessfulDownsampled = lastSuccessfulDownsampled;
    }
}
