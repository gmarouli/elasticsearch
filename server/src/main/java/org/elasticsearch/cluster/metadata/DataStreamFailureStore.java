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
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Holds the data stream failure store metadata that enable or disable the failure store of a data stream. Currently, it
 * supports the following configurations only explicitly enabling or disabling the failure store
 */
public record DataStreamFailureStore(@Nullable Boolean enabled) implements SimpleDiffable<DataStreamFailureStore>, ToXContentObject {
    public static final String FAILURE_STORE = "failure_store";
    public static final String ENABLED = "enabled";

    public static final ParseField ENABLED_FIELD = new ParseField(ENABLED);

    public static final ConstructingObjectParser<DataStreamFailureStore, Void> PARSER = new ConstructingObjectParser<>(
        FAILURE_STORE,
        false,
        (args, unused) -> new DataStreamFailureStore((Boolean) args[0])
    );

    static {
        PARSER.declareBoolean(ConstructingObjectParser.optionalConstructorArg(), ENABLED_FIELD);
    }

    /**
     * @param enabled, true when the failure is enabled, false when it's disabled, null when it depends on other configuration. Currently,
     *                 null value is not supported because there are no other arguments
     * @throws IllegalArgumentException when all the constructor arguments are null
     */
    public DataStreamFailureStore {
        if (enabled == null) {
            throw new IllegalArgumentException("Failure store configuration should have at least one non-null configuration value.");
        }
    }

    public static DataStreamFailureStore read(StreamInput in) throws IOException {
        return new DataStreamFailureStore(in.readOptionalBoolean());
    }

    public static Diff<DataStreamFailureStore> readDiffFrom(StreamInput in) throws IOException {
        return SimpleDiffable.readDiffFrom(DataStreamFailureStore::read, in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalBoolean(enabled);
    }

    @Override
    public String toString() {
        return Strings.toString(this, true, true);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (enabled != null) {
            builder.field(ENABLED_FIELD.getPreferredName(), enabled);
        }
        builder.endObject();
        return builder;
    }

    public static DataStreamFailureStore fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    /**
     * This class is only used in template configuration. It allows us to represent explicit null values and denotes that the fields
     * of the failure can be merged.
     */
    public static class Template extends ComposableOptions {

        public static final ObjectParser<ImmutableOpenMap.Builder<String, Object>, Void> PARSER = new ObjectParser<>(
            "failure_store_template"
        );

        static {
            PARSER.declareField(
                (map, v) -> map.put(ENABLED, v),
                p -> p.currentToken() == XContentParser.Token.VALUE_NULL ? null : p.booleanValue(),
                ENABLED_FIELD,
                ObjectParser.ValueType.BOOLEAN_OR_NULL
            );
        }

        public Template(Map<String, Object> optionsMap) {
            super(optionsMap);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            ComposableOptions.writeOptionalExplicitlyNullable(
                out,
                isDefined(ENABLED),
                getOptionAsBoolean(ENABLED),
                (StreamOutput::writeBoolean)
            );
        }

        public static Template read(StreamInput in) throws IOException {
            Map<String, Object> template = new HashMap<>();
            ComposableOptions.readOptionalExplicitlyNullable(in, template, ENABLED, StreamInput::readBoolean);
            return new Template(template);
        }

        public static DataStreamFailureStore.Template fromXContent(XContentParser parser) throws IOException {
            ImmutableOpenMap.Builder<String, Object> map = ImmutableOpenMap.builder();
            PARSER.parse(parser, map, null);
            return new Template(map.build());
        }

        @Nullable
        public DataStreamFailureStore toFailureStore() {
            if (isNullified()) {
                return null;
            }
            Boolean enabled = getOptionAsBoolean(ENABLED);
            return new DataStreamFailureStore(enabled);
        }

        @Override
        public ComposableOptions create(Map<String, Object> optionsMap) {
            return new DataStreamFailureStore.Template(optionsMap);
        }
    }
}
