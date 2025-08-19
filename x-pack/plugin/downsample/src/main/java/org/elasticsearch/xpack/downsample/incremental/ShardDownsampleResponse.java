/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.downsample.incremental;

import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;

public class ShardDownsampleResponse extends AcknowledgedResponse {

    private final ShardId shardId;
    private final long downsampledDocs;

    protected ShardDownsampleResponse(StreamInput in) throws IOException {
        super(in);
        shardId = new ShardId(in);
        downsampledDocs = in.readVLong();
    }

    protected ShardDownsampleResponse(boolean acknowledged, ShardId shardId, long downsampledDocs) {
        super(acknowledged);
        this.shardId = shardId;
        this.downsampledDocs = downsampledDocs;
    }

    public ShardId getShardId() {
        return shardId;
    }

    public long getDownsampledDocs() {
        return downsampledDocs;
    }
}
