/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.SimpleDiffable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

/**
 * Holds the data stream incremental downsampling configuration that enables different downsample layers.
 */
public record DataStreamDownsampling(List<DownsampledLayer> downsampledLayers)
    implements
        SimpleDiffable<DataStreamDownsampling>,
        ToXContentObject {

    public static final ParseField LAYERS = new ParseField("layers");

    @SuppressWarnings("unchecked")
    public static final ConstructingObjectParser<DataStreamDownsampling, Void> PARSER = new ConstructingObjectParser<>(
        "downsampling_layers",
        false,
        (args, unused) -> new DataStreamDownsampling((List<DownsampledLayer>) args[0])
    );

    static {
        PARSER.declareObjectArray(ConstructingObjectParser.constructorArg(), DownsampledLayer::fromXContent, LAYERS);
    }

    public DataStreamDownsampling {
        if (downsampledLayers == null) {
            throw new IllegalArgumentException("You must specify either at least one layer");
        }
        DownsampledLayer.validateRounds(downsampledLayers);
    }

    public DataStreamDownsampling(StreamInput in) throws IOException {
        this(in.readCollectionAsList(DownsampledLayer::new));
    }

    public static Diff<DataStreamDownsampling> readDiffFrom(StreamInput in) throws IOException {
        return SimpleDiffable.readDiffFrom(DataStreamDownsampling::new, in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeCollection(downsampledLayers);
    }

    @Override
    public String toString() {
        return Strings.toString(this, true, true);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.array(LAYERS.getPreferredName(), downsampledLayers);
        builder.endObject();
        return builder;
    }

    public static DataStreamDownsampling fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    /**
     * A round represents the configuration for when and how elasticsearch will downsample a backing index.
     * @param interval is the interval based on which we will aggregate the data
     * @param startTime is the start time of downsampling for this layer, when it's null it defaults to now.
     */
    public record DownsampledLayer(DateHistogramInterval interval, Long startTime) implements Writeable, ToXContentObject {

        public static final ParseField FIXED_INTERVAL_FIELD = new ParseField("interval");
        public static final ParseField START_TIME_FIELD = new ParseField("start_time");

        private static final ConstructingObjectParser<DownsampledLayer, Void> PARSER = new ConstructingObjectParser<>(
            "downsampled_layer",
            false,
            (args, unused) -> new DownsampledLayer((DateHistogramInterval) args[0], (Long) args[1])
        );

        static {
            PARSER.declareField(
                constructorArg(),
                p -> new DateHistogramInterval(p.text()),
                new ParseField(FIXED_INTERVAL_FIELD.getPreferredName()),
                ObjectParser.ValueType.STRING
            );
            PARSER.declareLong(ConstructingObjectParser.optionalConstructorArg(), START_TIME_FIELD);
        }

        public DownsampledLayer(StreamInput in) throws IOException {
            this(new DateHistogramInterval(in.readString()), in.readOptionalVLong());
        }

        static void validateRounds(List<DownsampledLayer> layers) {
            DownsampledLayer previous = null;
            for (DownsampledLayer layer : layers) {
                if (previous == null) {
                    previous = layer;
                } else {
                    validateSourceAndTargetIntervals(previous.interval(), layer.interval());
                }
            }
        }

        /**
         * This method validates the target downsampling configuration can be applied on an index that has been
         * already downsampled from the source configuration. The requirements are:
         * - The target interval needs to be greater than source interval
         * - The target interval needs to be a multiple of the source interval
         * throws an IllegalArgumentException to signal that the target interval is not acceptable
         */
        private static void validateSourceAndTargetIntervals(DateHistogramInterval source, DateHistogramInterval target) {
            long sourceMillis = source.estimateMillis();
            long targetMillis = target.estimateMillis();
            if (sourceMillis >= targetMillis) {
                // Downsampling interval must be greater than source interval
                throw new IllegalArgumentException(
                    "Downsampling interval [" + target + "] must be greater than the source index interval [" + source + "]."
                );
            } else if (targetMillis % sourceMillis != 0) {
                // Downsampling interval must be a multiple of the source interval
                throw new IllegalArgumentException(
                    "Downsampling interval [" + target + "] must be a multiple of the source index interval [" + source + "]."
                );
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(interval.toString());
            out.writeOptionalVLong(startTime);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(FIXED_INTERVAL_FIELD.getPreferredName(), interval.toString());
            if (startTime != null) {
                builder.field(START_TIME_FIELD.getPreferredName(), startTime);
            }
            builder.endObject();
            return builder;
        }

        public static DownsampledLayer fromXContent(XContentParser parser, Void context) throws IOException {
            return PARSER.parse(parser, context);
        }

        @Override
        public String toString() {
            return Strings.toString(this, true, true);
        }
    }
}
