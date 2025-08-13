/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.datastreams.downsampling;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

public record DataStreamDownsamplerParams(String dataStream, long startTime) implements PersistentTaskParams {

    public static final String NAME = "incremental-downsampling";
    private static final String DATA_STREAM_FIELD = "data_stream";
    private static final String START_TIME_FIELD = "start_time";
    @SuppressWarnings("unchecked")
    private static final ConstructingObjectParser<DataStreamDownsamplerParams, Void> PARSER = new ConstructingObjectParser<>(
        NAME,
        true,
        args -> new DataStreamDownsamplerParams((String) args[0], (long) args[1])
    );
    static {
        PARSER.declareString(constructorArg(), new ParseField(DATA_STREAM_FIELD));
        PARSER.declareLong(constructorArg(), new ParseField(START_TIME_FIELD));
    }

    @SuppressWarnings("unchecked")
    public DataStreamDownsamplerParams(StreamInput in) throws IOException {
        this(in.readString(), in.readLong());
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.INCREMENTAL_DOWNSAMPLING;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(dataStream);
        out.writeLong(startTime);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject().field(DATA_STREAM_FIELD, dataStream).field(START_TIME_FIELD, startTime);
        builder.endObject();
        return builder;
    }

    public static DataStreamDownsamplerParams fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }
}
