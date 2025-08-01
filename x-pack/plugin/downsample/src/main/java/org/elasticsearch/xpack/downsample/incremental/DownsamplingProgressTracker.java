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
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.xpack.downsample.incremental.IncrementalDownsamplingService.UTC;

/**
 * This class will try to wrap the API that will expose the downsampled time ranges of a data stream.
 * Every method represents a question we will need to answer for a data stream.
 */
public class DownsamplingProgressTracker {

    private static final TimeValue DOWNSAMPLING_DELAY = TimeValue.timeValueMinutes(0);
    private static final Logger logger = LogManager.getLogger(DownsamplingProgressTracker.class);

    private final Map<ShardId, DownsamplingShardMetadata> shardMetadata = new HashMap<>();
    private final Rounding rounding;
    private final long gracePeriod;
    private final TimeValue interval;
    private final long initialisationMillis;

    public DownsamplingProgressTracker(long initialisationMillis, DateHistogramInterval interval) {
        this(initialisationMillis, TimeValue.parseTimeValue(interval.toString(), "incremental-downsample-config"));
    }

    public DownsamplingProgressTracker(long initialisationMillis, TimeValue interval) {
        this.interval = interval;
        this.gracePeriod = 0;// Math.max(DOWNSAMPLING_DELAY.getMillis(), interval.getMillis());
        this.initialisationMillis = initialisationMillis;
        this.rounding = Rounding.builder(interval).timeZone(ZoneId.of("UTC")).build();
    }

    @Nullable
    TimeRange mostRecentTimeRangeToDownsample(ShardId shardId, Instant now, Instant startBound, Instant endBound) {
        // TODO-ID: initialisation time might need to be index start time when if this index has been rolled over.
        DownsamplingShardMetadata shardProgress = shardMetadata.computeIfAbsent(
            shardId,
            ignored -> new DownsamplingShardMetadata(
                shardId,
                rounding,
                gracePeriod,
                interval,
                Math.max(initialisationMillis, startBound.toEpochMilli())
            )
        );
        return shardProgress.getNextTimeRangeToDownsample(now, endBound);
    }

    void updateLastDownsampledBucket(ShardId shardId, Instant endTime) {
        shardMetadata.get(shardId).setLastSuccessfulDownsampled(endTime.toEpochMilli());
    }

    record TimeRange(Instant start, Instant end) {

        TimeRange(long start, long end) {
            this(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end));
        }

        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy hh:mm:ss", Locale.ROOT)
            .withZone(UTC.toZoneId());

        @Override
        public String toString() {
            return formatter.format(start) + " - " + formatter.format(end);
        }
    }

    public Instant roundToInterval(long millis) {
        return Instant.ofEpochMilli(rounding.prepareForUnknown().round(millis));
    }
}
