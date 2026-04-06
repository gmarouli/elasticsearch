/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.unsignedlong;

import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.LongValues;
import org.elasticsearch.index.fielddata.FieldData;
import org.elasticsearch.index.fielddata.FormattedDocValues;
import org.elasticsearch.index.fielddata.LeafNumericFieldData;
import org.elasticsearch.index.fielddata.SortedBinaryDocValues;
import org.elasticsearch.index.fielddata.SortedNumericLongValues;
import org.elasticsearch.index.fielddata.SortedNumericValues;
import org.elasticsearch.index.fielddata.plain.FormattedSortedLongDocValues;
import org.elasticsearch.script.field.DocValuesScriptFieldFactory;
import org.elasticsearch.script.field.ToScriptFieldFactory;
import org.elasticsearch.search.DocValueFormat;

import java.io.IOException;

import static org.elasticsearch.xpack.unsignedlong.UnsignedLongFieldMapper.sortableSignedLongToUnsigned;

public class UnsignedLongLeafFieldData implements LeafNumericFieldData {
    private final LeafNumericFieldData signedLongFD;
    protected final ToScriptFieldFactory<SortedNumericValues> toScriptFieldFactory;

    UnsignedLongLeafFieldData(LeafNumericFieldData signedLongFD, ToScriptFieldFactory<SortedNumericValues> toScriptFieldFactory) {
        this.signedLongFD = signedLongFD;
        this.toScriptFieldFactory = toScriptFieldFactory;
    }

    @Override
    public SortedNumericValues getValues() {
        final SortedNumericValues values = signedLongFD.getValues();
        final LongValues singleValues = values.unwrapSingletonLongValues();
        if (singleValues != null) {
            return new SortedNumericLongValues.SingletonSortedNumericLongValues(singleValues) {
                @Override
                public double nextDoubleValue() throws IOException {
                    return convertUnsignedLongToDouble(singleValues.longValue());
                }

                @Override
                public DoubleValues unwrapSingletonDoubleValues() {
                    SortedNumericLongValues longValues = this;
                    return new DoubleValues() {
                        @Override
                        public double doubleValue() throws IOException {
                            return convertUnsignedLongToDouble(longValues.nextLongValue());
                        }

                        @Override
                        public boolean advanceExact(int doc) throws IOException {
                            return longValues.advanceExact(doc);
                        }
                    };
                }
            };
        } else {
            return new SortedNumericLongValues() {

                @Override
                public boolean advanceExact(int target) throws IOException {
                    return values.advanceExact(target);
                }

                @Override
                public double nextDoubleValue() throws IOException {
                    return convertUnsignedLongToDouble(values.nextLongValue());
                }

                @Override
                public long nextLongValue() throws IOException {
                    return values.nextLongValue();
                }

                @Override
                public int docValueCount() {
                    return values.docValueCount();
                }
            };
        }
    }

    @Override
    public DocValuesScriptFieldFactory getScriptFieldFactory(String name) {
        return toScriptFieldFactory.getScriptFieldFactory(getValues(), name);
    }

    @Override
    public SortedBinaryDocValues getBytesValues() {
        return FieldData.doubleToString(getValues());
    }

    @Override
    public long ramBytesUsed() {
        return signedLongFD.ramBytesUsed();
    }

    @Override
    public FormattedDocValues getFormattedValues(DocValueFormat format) {
        return new FormattedSortedLongDocValues(getValues(), format);
    }

    static double convertUnsignedLongToDouble(long value) {
        if (value < 0L) {
            return sortableSignedLongToUnsigned(value); // add 2 ^ 63
        } else {
            // add 2 ^ 63 as a double to make sure there is no overflow and final result is positive
            return 0x1.0p63 + value;
        }
    }
}
