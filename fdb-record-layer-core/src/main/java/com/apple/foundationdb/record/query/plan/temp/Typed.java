/*
 * Atom.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2021 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.temp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Provides {@link Type} information about result set. Implementations of this interface allow the caller to inspect
 * the {@link Type} of their result sets.
 */
public interface Typed {

    /**
     * Returns the {@link Type} of the result set.
     * @return the {@link Type} of the result set.
     */
    @Nonnull
    Type getResultType();

    /**
     * Safe-casts the {@link Typed} instance to another type.
     *
     * @param clazz marker object.
     * @param <T> The type to cast to.
     * @return if cast is successful, an {@link Optional} containing the instance cast to {@link T}, otherwise an
     * empty {@link Optional}.
     */
    default <T extends Typed> Optional<T> narrowMaybe(@Nonnull final Class<T> clazz) {
        if (clazz.isInstance(this)) {
            return Optional.of(clazz.cast(this));
        } else {
            return Optional.empty();
        }
    }

    /**
     * Returns a human-friendly textual representation of both the type-producing instance and its result set {@link Type}.
     *
     * @param formatter The formatter used to format the textual representation.
     * @return a human-friendly textual representation of both the type-producing instance and its result set {@link Type}.
     */
    @Nonnull
    String explain(@Nonnull final Formatter formatter);

    /**
     * Utility class for producing {@link Type} information of a given literal object.
     */
    class TypedLiteral implements Typed {
        /**
         * The {@link Type} of the literal.
         */
        @Nonnull
        private final Type resultType;

        /**
         * The literal.
         */
        @Nullable
        private final Object value;

        /**
         * Creates a instance that automatically wraps {@link Type} information of a literal.
         *
         * @param resultTypeCode The literal type code.
         * @param value the literal.
         */
        public TypedLiteral(@Nonnull final Type.TypeCode resultTypeCode, @Nullable final Object value) {
            this.resultType = Type.primitiveType(resultTypeCode);
            this.value = value;
        }

        /**
         * {@inheritDoc}
         */
        @Nonnull
        @Override
        public Type getResultType() {
            return resultType;
        }

        /**
         * {@inheritDoc}
         */
        @Nonnull
        @Override
        public String explain(@Nonnull final Formatter formatter) {
            throw new UnsupportedOperationException("should not be called");
        }

        /**
         * Returns the literal.
         * @return The literal.
         */
        @Nullable
        public Object getValue() {
            return value;
        }
    }
}
