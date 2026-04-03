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

import java.io.IOException;

/**
 * A multivalued version of {@link LongValues}
 */
public abstract class SortedNumericLongValues implements SortedNumericValues {

    /**
     * A {@link SortedNumericLongValues} instance that does not have a value for any document
     */
    public static SortedNumericLongValues EMPTY = new SortedNumericLongValues() {
        @Override
        public boolean advanceExact(int target) {
            return false;
        }

        @Override
        public long nextLongValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int docValueCount() {
            throw new UnsupportedOperationException();
        }
    };

    public double nextDoubleValue() throws IOException {
        return nextLongValue();
    }

    /**
     * Converts a {@link LongValues} to a {@link SortedNumericLongValues}
     */
    public static SortedNumericLongValues singleton(LongValues values) {
        return new SingletonSortedNumericLongValues(values);
    }

    private static class SingletonSortedNumericLongValues extends SortedNumericLongValues {

        private final LongValues values;

        private SingletonSortedNumericLongValues(LongValues values) {
            this.values = values;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
            return values.advanceExact(target);
        }

        @Override
        public long nextLongValue() throws IOException {
            return values.longValue();
        }

        @Override
        public int docValueCount() {
            return 1;
        }

        @Override
        public DoubleValues unwrapSingletonDoubleValues() {
            return new DoubleValues() {
                @Override
                public double doubleValue() throws IOException {
                    return values.longValue();
                }

                @Override
                public boolean advanceExact(int doc) throws IOException {
                    return values.advanceExact(doc);
                }
            };
        }

        @Override
        public LongValues unwrapSingletonLongValues() {
            return values;
        }
    }

    /**
     * Converts a {@link SortedNumericDocValues} iterator to a {@link SortedNumericLongValues}
     *
     * Note that if the wrapped iterator can be unwrapped to a singleton {@link NumericDocValues}
     * instance, then the returned {@link SortedNumericLongValues} can also be unwrapped to
     * a {@link LongValues} instance via {@link SortedNumericValues#unwrapSingletonLongValues()}
     */
    public static SortedNumericLongValues wrap(SortedNumericDocValues values) {
        NumericDocValues singleton = DocValues.unwrapSingleton(values);
        if (singleton != null) {
            return new SingletonSortedNumericLongValues(new LongValues() {
                @Override
                public long longValue() throws IOException {
                    return singleton.longValue();
                }

                @Override
                public boolean advanceExact(int doc) throws IOException {
                    return singleton.advanceExact(doc);
                }
            });
        }
        return new SortedNumericLongValues() {
            @Override
            public boolean advanceExact(int target) throws IOException {
                return values.advanceExact(target);
            }

            @Override
            public long nextLongValue() throws IOException {
                return values.nextValue();
            }

            @Override
            public int docValueCount() {
                return values.docValueCount();
            }
        };
    }
}
