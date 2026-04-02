/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.fielddata;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;

public class IterableSortedDoubleValuesTests extends AbstractFieldDataTestCase {

    @Override
    protected boolean hasDocValues() {
        return true;
    }

    public void testNextDoubleValue() throws Exception {
        var values = initialise("test");
        assertThat(values.docID(), equalTo(-1));
        assertThat(values.advanceExact(0), equalTo(true));
        assertThat(values.nextDoubleValue(), equalTo(5.3));
        assertThat(values.nextDoubleValue(), equalTo(10.0993));
    }

    public void testNextLongValue() throws Exception {
        var values = initialise("test");
        assertThat(values.docID(), equalTo(-1));
        assertThat(values.advanceExact(0), equalTo(true));
        assertThat(values.nextLongValue(), equalTo(5));
        assertThat(values.nextLongValue(), equalTo(10));
    }

    @Override
    protected String getFieldDataType() {
        return "double";
    }

    private IterableSortedNumericValues initialise(String indexName) throws Exception {
        String mapping = Strings.toString(
            XContentFactory.jsonBuilder()
                .startObject()
                .startObject(indexName)
                .startObject("properties")
                .startObject("field")
                .field("type", "double")
                .endObject()
                .endObject()
                .endObject()
                .endObject()
        );

        DocumentMapper mapper = mapperService.merge(indexName, new CompressedXContent(mapping), MapperService.MergeReason.MAPPING_UPDATE);

        XContentBuilder doc = XContentFactory.jsonBuilder()
            .startObject()
            .startArray("field")
            .value(5.3)
            .value(10.0993)
            .endArray()
            .endObject();
        ParsedDocument d = mapper.parse(new SourceToParse("1", BytesReference.bytes(doc), XContentType.JSON));
        writer.addDocument(d.rootDoc());

        IndexFieldData<?> indexFieldData = getForField("field");
        List<LeafReaderContext> readers = refreshReader();
        assertEquals(1, readers.size());
        LeafReaderContext reader = readers.get(0);

        LeafFieldData fieldData = indexFieldData.load(reader);
        return ((LeafNumericFieldData) fieldData).getIterableNumericValues();
    }
}
