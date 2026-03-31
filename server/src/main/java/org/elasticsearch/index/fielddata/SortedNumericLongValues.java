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
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.LongValues;

import java.io.IOException;

/**
 * A multivalued version of {@link LongValues}
 */
public abstract class SortedNumericLongValues {
    /**
     * A {@link SortedNumericLongValues} instance that does not have a value for any document
     */
    public static SortedNumericLongValues EMPTY = new SortedNumericLongValues() {
        @Override
        public boolean advanceExact(int target) {
            return false;
        }

        @Override
        public int advance(int target) throws IOException {
            return DocIdSetIterator.NO_MORE_DOCS;
        }

        @Override
        public int docID() {
            return DocIdSetIterator.NO_MORE_DOCS;
        }

        @Override
        public long nextValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int docValueCount() {
            throw new UnsupportedOperationException();
        }
    };

    /** Advance the iterator to exactly {@code target} and return whether
     *  {@code target} has a value.
     *  {@code target} must be greater than or equal to the current
     *  doc ID and must be a valid doc ID, ie. &ge; 0 and
     *  &lt; {@code maxDoc}.*/
    public abstract boolean advanceExact(int target) throws IOException;

    /**
     * Advances to the first beyond the current whose document number is greater than or equal to
     * {@code target}, and returns the document number itself. Exhausts the iterator and returns {@link
     * org.apache.lucene.search.DocIdSetIterator#NO_MORE_DOCS} if {@code target} is greater than the
     * highest document number in the set.
     *
     * <p><b>NOTE:</b>The behavior of this method is <b>undefined</b> when called with {@code target}
     * is less or equal than the current doc id, or after the iterator has exhausted. Both cases may
     * result in unpredicted behavior.
     */
    public abstract int advance(int target) throws IOException;

    /**
     * Returns the following
     *  {@code -1}, if {@link #nextValue()} ()}, {@link #advanceExact(int)} or {@link #advance(int)} were not called yet.
     *  {@link org.apache.lucene.search.DocIdSetIterator#NO_MORE_DOCS} if the iterator has exhausted.
     *  otherwise, the doc ID it is currently on.
     */
    public abstract int docID();

    /**
     * Iterates to the next value in the current document. Do not call this more than
     * {@link #docValueCount} times for the document.
     */
    public abstract long nextValue() throws IOException;

    /**
     * Retrieves the number of values for the current document.  This must always
     * be greater than zero.
     * It is illegal to call this method after {@link #advanceExact(int)}
     * returned {@code false}.
     */
    public abstract int docValueCount();

    public LongValues unwrapSingleton() {
        return null;
    }

    public static class Singleton extends SortedNumericLongValues {

        protected final NumericDocValues values;
        private LongValues longValues;

        private Singleton(NumericDocValues values) {
            this.values = values;
        }

        protected Singleton(Singleton other) {
            this.values = other.values;
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
            return values.advanceExact(target);
        }

        @Override
        public int advance(int target) throws IOException {
            return values.advance(target);
        }

        @Override
        public int docID() {
            return values.docID();
        }

        @Override
        public long nextValue() throws IOException {
            return values.longValue();
        }

        @Override
        public int docValueCount() {
            return 1;
        }

        public LongValues unwrapSingleton() {
            if (longValues == null) {
                longValues = new LongValues() {

                    @Override
                    public long longValue() throws IOException {
                        return values.longValue();
                    }

                    @Override
                    public boolean advanceExact(int doc) throws IOException {
                        return values.advanceExact(doc);
                    }
                };
            }
            return longValues;
        }
    }

    /**
     * Converts a {@link SortedNumericDocValues} iterator to a {@link SortedNumericLongValues}
     *
     * Note that if the wrapped iterator can be unwrapped to a singleton {@link NumericDocValues}
     * instance, then the returned {@link SortedNumericLongValues} can also be unwrapped to
     * a {@link LongValues} instance via {@link SortedNumericLongValues#unwrapSingleton()}
     */
    public static SortedNumericLongValues wrap(SortedNumericDocValues values) {
        NumericDocValues singleton = DocValues.unwrapSingleton(values);
        if (singleton != null) {
            return new Singleton(singleton);
        }
        return new SortedNumericLongValues() {
            @Override
            public boolean advanceExact(int target) throws IOException {
                return values.advanceExact(target);
            }

            @Override
            public int advance(int target) throws IOException {
                return values.advance(target);
            }

            @Override
            public int docID() {
                return values.docID();
            }

            @Override
            public long nextValue() throws IOException {
                return values.nextValue();
            }

            @Override
            public int docValueCount() {
                return values.docValueCount();
            }
        };
    }
}
