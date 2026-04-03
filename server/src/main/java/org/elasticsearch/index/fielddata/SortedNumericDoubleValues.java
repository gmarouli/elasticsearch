/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.fielddata;

import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.LongValues;
import org.apache.lucene.util.NumericUtils;

import java.io.IOException;

/**
 * Clone of {@link SortedNumericDocValues} for double values.
 */
public abstract class SortedNumericDoubleValues implements SortedNumericValues {

    /** Sole constructor. (For invocation by subclass
     * constructors, typically implicit.) */
    protected SortedNumericDoubleValues() {}

    public long nextLongValue() throws IOException {
        return (long) nextDoubleValue();
    }

    /**
     * Converts a {@link DoubleValues} to a {@link SortedNumericDoubleValues}
     */
    public static SortedNumericDoubleValues singleton(DoubleValues values) {
        return new SortedNumericDoubleValues.SingletonSortedNumericDoubleValues(values);
    }

    private static class SingletonSortedNumericDoubleValues extends SortedNumericDoubleValues {

        private final DoubleValues values;

        private SingletonSortedNumericDoubleValues(DoubleValues values) {
            this.values = values;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
            return values.advanceExact(target);
        }

        @Override
        public double nextDoubleValue() throws IOException {
            return values.doubleValue();
        }

        @Override
        public int docValueCount() {
            return 1;
        }

        @Override
        public DoubleValues unwrapSingletonDoubleValues() {
            return values;
        }

        @Override
        public LongValues unwrapSingletonLongValues() {
            return new LongValues() {
                @Override
                public long longValue() throws IOException {
                    return (long) values.doubleValue();
                }

                @Override
                public boolean advanceExact(int doc) throws IOException {
                    return values.advanceExact(doc);
                }
            };
        }
    }

    /**
     * Converts a {@link SortedNumericDocValues} iterator to a {@link SortedNumericDoubleValues}
     *
     * Note that if the wrapped iterator can be unwrapped to a singleton {@link NumericDocValues}
     * instance, then the returned {@link SortedNumericDoubleValues} can also be unwrapped to
     * a {@link DoubleValues} instance via {@link SortedNumericValues#unwrapSingletonDoubleValues()}
     */
    public static SortedNumericDoubleValues wrap(SortedNumericDocValues values) {
        NumericDocValues singleton = DocValues.unwrapSingleton(values);
        if (singleton != null) {
            return new SortedNumericDoubleValues.SingletonSortedNumericDoubleValues(new DoubleValues() {
                @Override
                public double doubleValue() throws IOException {
                    return NumericUtils.sortableLongToDouble(singleton.longValue());
                }

                @Override
                public boolean advanceExact(int doc) throws IOException {
                    return singleton.advanceExact(doc);
                }
            });
        }
        return new SortedNumericDoubleValues() {
            @Override
            public boolean advanceExact(int target) throws IOException {
                return values.advanceExact(target);
            }

            @Override
            public double nextDoubleValue() throws IOException {
                return NumericUtils.sortableLongToDouble(values.nextValue());
            }

            @Override
            public int docValueCount() {
                return values.docValueCount();
            }
        };
    }
}
