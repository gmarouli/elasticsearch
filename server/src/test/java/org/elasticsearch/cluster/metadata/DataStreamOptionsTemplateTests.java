/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.test.AbstractXContentSerializingTestCase;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Map;

public class DataStreamOptionsTemplateTests extends AbstractXContentSerializingTestCase<DataStreamOptions.Template> {

    @Override
    protected Writeable.Reader<DataStreamOptions.Template> instanceReader() {
        return DataStreamOptions.Template::read;
    }

    @Override
    protected DataStreamOptions.Template createTestInstance() {
        return randomDataStreamOptions();
    }

    public static DataStreamOptions.Template randomDataStreamOptions() {
        return switch (randomIntBetween(0, 4)) {
            case 0 -> DataStreamOptions.Template.EMPTY;
            case 1 -> new DataStreamOptions.Template(
                Map.of("failure_store", new DataStreamFailureStore.Template(Map.of("enabled", false)))
            );
            case 2 -> new DataStreamOptions.Template(Map.of("failure_store", new DataStreamFailureStore.Template(Map.of("enabled", true))));
            case 3 -> new DataStreamOptions.Template(null);
            case 4 -> new DataStreamOptions.Template(Map.of("failure_store", new DataStreamFailureStore.Template(null)));
            default -> throw new IllegalArgumentException("Illegal randomisation branch");
        };
    }

    @Override
    protected DataStreamOptions.Template mutateInstance(DataStreamOptions.Template instance) throws IOException {
        return randomValueOtherThan(instance, DataStreamOptionsTemplateTests::randomDataStreamOptions);
    }

    @Override
    protected DataStreamOptions.Template doParseInstance(XContentParser parser) throws IOException {
        return DataStreamOptions.Template.fromXContent(parser);
    }
}
