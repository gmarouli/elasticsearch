/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

/**
 * This represents all the data stream options configuration that can be explicitly nullified and merged during template composition.
 */
public abstract class ComposableOptions implements Writeable, ToXContent {

    public static final String SKIP_EXPLICIT_NULLS = "skip_explicit_nulls";

    private final Map<String, Object> optionsMap;

    public ComposableOptions(Map<String, Object> optionsMap) {
        this.optionsMap = optionsMap;
    }

    @Nullable
    public Boolean getOptionAsBoolean(String field) {
        Object value = getOptionsMap().get(field);
        if (value == null) {
            return null;
        }
        return Booleans.parseBoolean(value.toString());
    }

    /**
     * Gets the value of the field or null. Null here can mean either that the field is assigned the null value or that it's missing.
     */
    @Nullable
    public Object get(String field) {
        return this.getOptionsMap().get(field);
    }

    /**
     * @return true if the field is defined even if its value is null.
     */
    public boolean isDefined(String field) {
        return getOptionsMap().containsKey(field);
    }

    /**
     * @return true if these composable options have been explicitly nullified.
     */
    public boolean isNullified() {
        return getOptionsMap() == null;
    }

    /**
     * @return An immutable internal map that holds the options
     */
    Map<String, Object> getOptionsMap() {
        return optionsMap;
    };

    /**
     * Converts the template to XContent, when the parameter {@link #SKIP_EXPLICIT_NULLS} is set to true, it will not display any explicit
     * nulls
     */
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        boolean skipExplicitNulls = params.paramAsBoolean(SKIP_EXPLICIT_NULLS, false);
        for (String field : getOptionsMap().keySet()) {
            if (getOptionsMap().containsKey(field)) {
                Object value = getOptionsMap().get(field);
                if (value == null && skipExplicitNulls == false) {
                    builder.nullField(field);
                } else {
                    builder.field(field, value);
                }
            }
        }
        builder.endObject();
        return builder;
    }

    /**
     * Recursively applies a set of composable options on top of the current one.
     * @param newOptions the new values to be applied, null means that the new value set is explicitly nullified.
     * @param factory a constructor of the concrete class found in this level
     * @return new composable options that have applied the new values on top of the existing ones.
     */
    public <T extends ComposableOptions> T merge(T newOptions, Function<Map<String, Object>, T> factory) {
        if (newOptions == null || newOptions.isNullified()) {
            return null;
        }
        if (this.isNullified()) {
            return newOptions;
        }
        ImmutableOpenMap.Builder<String, Object> builder = ImmutableOpenMap.builder(this.getOptionsMap());
        for (Map.Entry<String, Object> entry : newOptions.getOptionsMap().entrySet()) {
            if (this.getOptionsMap().get(entry.getKey()) instanceof ComposableOptions oldValue
                && entry.getValue() instanceof ComposableOptions newValue) {
                builder.put(entry.getKey(), oldValue.merge(newValue, oldValue::create));
            } else {
                builder.put(entry.getKey(), entry.getValue());
            }
        }

        return factory.apply(builder.build());
    }

    /**
     * Writes a single optional and explicitly nullable value. This method is in direct relation with the
     * {@link #readOptionalExplicitlyNullable(StreamInput, Map, String, Reader)} which reads the respective value.
     * @throws IOException
     */
    static <T> void writeOptionalExplicitlyNullable(StreamOutput out, boolean isDefined, T value, Writeable.Writer<T> writer)
        throws IOException {
        boolean isExplicitNull = isDefined && value == null;
        out.writeBoolean(isDefined);
        if (isDefined) {
            out.writeBoolean(isExplicitNull);
            if (isExplicitNull == false) {
                writer.write(out, value);
            }
        }
    }

    static <T> void readOptionalExplicitlyNullable(StreamInput in, Map<String, Object> template, String field, Reader<T> reader)
        throws IOException {
        boolean isDefined = in.readBoolean();
        if (isDefined == false) {
            return;
        }
        boolean isExplicitNull = in.readBoolean();
        if (isExplicitNull) {
            template.put(field, null);
            return;
        }
        T value = reader.read(in);
        template.put(field, value);
    }

    /**
     * The factory method of the subclasses
     */
    abstract ComposableOptions create(Map<String, Object> optionsMap);
}
