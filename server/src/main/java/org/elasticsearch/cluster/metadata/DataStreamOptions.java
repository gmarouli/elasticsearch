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
 * Holds data stream dedicated configuration options such as failure store, (in the future lifecycle). Currently, it
 * supports the following configurations:
 * - failure store
 */
public record DataStreamOptions(@Nullable DataStreamFailureStore failureStore)
    implements
        SimpleDiffable<DataStreamOptions>,
        ToXContentObject {

    public static final String FAILURE_STORE = "failure_store";
    public static final ParseField FAILURE_STORE_FIELD = new ParseField(FAILURE_STORE);
    public static final DataStreamOptions FAILURE_STORE_ENABLED = new DataStreamOptions(new DataStreamFailureStore(true));
    public static final DataStreamOptions FAILURE_STORE_DISABLED = new DataStreamOptions(new DataStreamFailureStore(false));
    public static final DataStreamOptions EMPTY = new DataStreamOptions();

    public static final ConstructingObjectParser<DataStreamOptions, Void> PARSER = new ConstructingObjectParser<>(
        "options",
        false,
        (args, unused) -> new DataStreamOptions((DataStreamFailureStore) args[0])
    );

    static {
        PARSER.declareObjectOrNull(
            ConstructingObjectParser.optionalConstructorArg(),
            (p, c) -> DataStreamFailureStore.fromXContent(p),
            null,
            FAILURE_STORE_FIELD
        );
    }

    public DataStreamOptions() {
        this(null);
    }

    public static DataStreamOptions read(StreamInput in) throws IOException {
        return new DataStreamOptions(in.readOptionalWriteable(DataStreamFailureStore::read));
    }

    public static Diff<DataStreamOptions> readDiffFrom(StreamInput in) throws IOException {
        return SimpleDiffable.readDiffFrom(DataStreamOptions::read, in);
    }

    public boolean isEmpty() {
        return failureStore == null;
    }

    /**
     * Determines if this data stream has its failure store enabled or not. Currently, the failure store
     * is enabled only when a user has explicitly requested it.
     * @return true, if the user has explicitly enabled the failure store.
     */
    public boolean isFailureStoreEnabled() {
        return failureStore != null && Boolean.TRUE.equals(failureStore.enabled());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalWriteable(failureStore);
    }

    @Override
    public String toString() {
        return Strings.toString(this, true, true);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (failureStore != null) {
            builder.field(FAILURE_STORE_FIELD.getPreferredName(), failureStore);
        }
        builder.endObject();
        return builder;
    }

    public static DataStreamOptions fromXContent(XContentParser parser) throws IOException {
        return PARSER.parse(parser, null);
    }

    /**
     * This class is only used in template configuration. It allows us to represent explicit null values and denotes that the fields
     * of the failure can be merged.
     */
    public static class Template extends ComposableOptions {
        public static final Template EMPTY = new Template(Map.of());

        public Template(Map<String, Object> template) {
            super(template);
        }

        public static final ObjectParser<ImmutableOpenMap.Builder<String, Object>, Void> PARSER = new ObjectParser<>("data_stream_options");

        static {
            PARSER.declareObjectOrNull(
                (map, v) -> map.put(FAILURE_STORE, v),
                (p, s) -> DataStreamFailureStore.Template.fromXContent(p),
                null,
                FAILURE_STORE_FIELD
            );
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            ComposableOptions.writeOptionalExplicitlyNullable(
                out,
                isDefined(FAILURE_STORE),
                (DataStreamFailureStore.Template) get(FAILURE_STORE),
                (o, value) -> value.writeTo(o)
            );
        }

        public static Template read(StreamInput in) throws IOException {
            Map<String, Object> template = new HashMap<>();
            ComposableOptions.readOptionalExplicitlyNullable(in, template, FAILURE_STORE, DataStreamFailureStore.Template::read);
            return new Template(template);
        }

        public static Template fromXContent(XContentParser parser) throws IOException {
            ImmutableOpenMap.Builder<String, Object> map = ImmutableOpenMap.builder();
            PARSER.parse(parser, map, null);
            return new Template(map.build());
        }

        @Nullable
        public DataStreamOptions toDataStreamOptions() {
            if (isNullified()) {
                return null;
            }
            DataStreamFailureStore.Template failureStoreTemplate = (DataStreamFailureStore.Template) get(FAILURE_STORE);
            if (failureStoreTemplate == null) {
                return DataStreamOptions.EMPTY;
            }
            return new DataStreamOptions(failureStoreTemplate.toFailureStore());
        }

        @Override
        public ComposableOptions create(Map<String, Object> optionsMap) {
            return new Template(optionsMap);
        }
    }
}
