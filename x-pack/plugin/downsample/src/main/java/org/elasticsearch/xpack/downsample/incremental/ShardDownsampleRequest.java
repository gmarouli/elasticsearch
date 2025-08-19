/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.downsample.DownsampleConfig;
import org.elasticsearch.action.support.single.shard.SingleShardRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;

/**
 * A request to incrementally downsample a shard for specified time range and interval.
 */
public class ShardDownsampleRequest extends SingleShardRequest<ShardDownsampleRequest> {

    private final DownsampleConfig downsampleConfig;
    private final long startTime;
    private final long endTime;
    private final ShardId shardId;
    private final String targetLayer;
    final String[] dimensions;
    final String[] metrics;
    final String[] labels;

    public ShardDownsampleRequest(
        ShardId shardId,
        DownsampleConfig downsampleConfig,
        long startTime,
        long endTime,
        String targetLayer,
        String[] dimensions,
        String[] metrics,
        String[] labels
    ) {
        this.index = shardId.getIndexName();
        this.shardId = shardId;
        this.downsampleConfig = downsampleConfig;
        this.startTime = startTime;
        this.endTime = endTime;
        this.targetLayer = targetLayer;
        this.dimensions = dimensions;
        this.metrics = metrics;
        this.labels = labels;
    }

    ShardDownsampleRequest(StreamInput in) throws IOException {
        super(in);
        shardId = new ShardId(in);
        downsampleConfig = in.readNamedWriteable(DownsampleConfig.class);
        startTime = in.readVLong();
        endTime = in.readVLong();
        targetLayer = in.readString();
        dimensions = in.readArray(StreamInput::readString, String[]::new);
        metrics = in.readArray(StreamInput::readString, String[]::new);
        labels = in.readArray(StreamInput::readString, String[]::new);

    }

    @Override
    public ActionRequestValidationException validate() {
        return super.validateNonNullIndex();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        shardId.writeTo(out);
        out.writeNamedWriteable(downsampleConfig);
        out.writeVLong(startTime);
        out.writeVLong(endTime);
        out.writeString(targetLayer);
        out.writeArray(StreamOutput::writeString, dimensions);
        out.writeArray(StreamOutput::writeString, metrics);
        out.writeArray(StreamOutput::writeString, labels);
    }

    public ShardId getShardId() {
        return shardId;
    }

    public DownsampleConfig getDownsampleConfig() {
        return downsampleConfig;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public String getTargetLayer() {
        return targetLayer;
    }

    public String[] getDimensions() {
        return dimensions;
    }

    public String[] getMetrics() {
        return metrics;
    }

    public String[] getLabels() {
        return labels;
    }
}
