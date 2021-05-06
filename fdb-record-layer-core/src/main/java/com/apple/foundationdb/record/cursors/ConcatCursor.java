/*
 * ConcatCursor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2019 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.cursors;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorContinuation;
import com.apple.foundationdb.record.RecordCursorProto;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.RecordCursorVisitor;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.util.TriFunction;
import com.apple.foundationdb.tuple.ByteArrayUtil2;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * A cursor that returns the elements of a first cursor followed by the elements of a second cursor.
 * Each cursor is generated by a provided function which is responsible for generating the cursor
 * in accordance with the FDBContext, ScanProperties, and Continuation in effect for ConcatCursor.
 *
 * @param <T> the type of elements of the cursor
 */
@API(API.Status.EXPERIMENTAL)
public class ConcatCursor<T> implements RecordCursor<T> {

    @Nonnull
    private final FDBRecordContext context;
    @Nonnull
    private final ScanProperties scanProperties;
    @Nonnull
    private final TriFunction<FDBRecordContext, ScanProperties, byte[], RecordCursor<T>> firstFunction;
    @Nonnull
    private final TriFunction<FDBRecordContext, ScanProperties, byte[], RecordCursor<T>> secondFunction;
    @Nullable
    private RecordCursor<T> firstCursor;
    @Nullable
    private RecordCursor<T> secondCursor;
    @Nullable
    private RecordCursorResult<T> nextResult;
    @Nullable
    private byte[] currentCursorContinuation;
    private int rowLimit;

    @API(API.Status.EXPERIMENTAL)
    public ConcatCursor(@Nonnull FDBRecordContext context,
                        @Nonnull ScanProperties scanProperties,
                        @Nonnull TriFunction<FDBRecordContext, ScanProperties, byte[], RecordCursor<T>> func1,
                        @Nonnull TriFunction<FDBRecordContext, ScanProperties, byte[], RecordCursor<T>> func2,
                        @Nullable byte[] continuation) {

        this.context = context;
        this.scanProperties = scanProperties;
        this.rowLimit = scanProperties.getExecuteProperties().getReturnedRowLimit();

        //reverse the cursors if reverse scan
        if (!scanProperties.isReverse()) {
            this.firstFunction = func1;
            this.secondFunction = func2;
        } else {
            this.firstFunction = func2;
            this.secondFunction = func1;
        }

        currentCursorContinuation = null;
        if (continuation != null) {
            try {
                //determine which cursor the continuation applies to
                RecordCursorProto.ConcatContinuation concatContinuation = RecordCursorProto.ConcatContinuation.parseFrom(continuation);
                currentCursorContinuation = concatContinuation.getContinuation().toByteArray();
                if (concatContinuation.hasSecond() && concatContinuation.getSecond() == true) {
                    this.secondCursor = secondFunction.apply(context, scanProperties, currentCursorContinuation);
                    this.firstCursor = null;
                } else {
                    this.firstCursor = firstFunction.apply(context, scanProperties, currentCursorContinuation);
                    this.secondCursor = null;
                }
            } catch (InvalidProtocolBufferException ex) {
                throw new RecordCoreException("Error parsing ConcatCursor continuation", ex)
                        .addLogInfo("raw_bytes", ByteArrayUtil2.loggable(continuation));
            }
        } else {
            //no continuation, start at the beginning of the first cursor
            this.firstCursor = firstFunction.apply(context, scanProperties, currentCursorContinuation);
            this.secondCursor = null;
            this.currentCursorContinuation = null;
        }
    }

    @Nonnull
    @Override
    public CompletableFuture<RecordCursorResult<T>> onNext() {
        if (secondCursor == null) {
            return firstCursor.onNext().thenCompose(result -> {
                if (result.hasNext() || !result.getNoNextReason().isSourceExhausted()) {
                    return CompletableFuture.completedFuture(result);
                } else {
                    Function<ExecuteProperties, ExecuteProperties> f = e -> e.toBuilder().setReturnedRowLimit(rowLimit).build(); //enforce row limit
                    secondCursor = secondFunction.apply(context, this.scanProperties.with(f), null); //start at beginning of second cursor
                    return secondCursor.onNext();
                }
            }).thenApply(this::postProcess);
        } else {
            return secondCursor.onNext().thenApply(this::postProcess);
        }
    }

    // return concat result from underlying cursor result
    @Nonnull
    private RecordCursorResult<T> postProcess(RecordCursorResult<T> result) {
        nextResult = getConcatResult(result);
        return nextResult;
    }

    //if haven't exhausted both cursors and no limits reached, form a result that encapsulates the value returned by
    //the current underlying cursor, plus a continuation that allows us to restart at same cursor at its next position
    @Nonnull
    private RecordCursorResult<T> getConcatResult(RecordCursorResult<T> nextResult) {
        @Nonnull RecordCursorResult<T> concatResult;
        if (!nextResult.hasNext()) {
            if (secondCursor != null && nextResult.getNoNextReason().isSourceExhausted()) {
                concatResult = RecordCursorResult.exhausted(); //continuation not valid here
            } else {
                concatResult = RecordCursorResult.withoutNextValue(new ConcatCursorContinuation(secondCursor != null, nextResult), nextResult.getNoNextReason());
            }
        } else {
            concatResult = RecordCursorResult.withNextValue(nextResult.get(), new ConcatCursorContinuation(secondCursor != null, nextResult));
            rowLimit = Integer.max(0, rowLimit - 1);
        }
        return concatResult;
    }

    @Override
    public void close() {
        if (secondCursor != null) {
            secondCursor.close();
        }

        if (firstCursor != null) {
            firstCursor.close();
        }
    }

    @Nonnull
    @Override
    public Executor getExecutor() {
        return context.getExecutor();
    }

    @Override
    public boolean accept(@Nonnull RecordCursorVisitor visitor) {
        if (visitor.visitEnter(this)) {
            if (secondCursor == null) {
                firstCursor.accept(visitor);
            } else {
                secondCursor.accept(visitor);
            }
        }
        return visitor.visitLeave(this);
    }

    //form a continuation that allows us to restart with the current cursor at its next position
    private class ConcatCursorContinuation implements RecordCursorContinuation {
        @Nonnull
        private final RecordCursorResult<T> nextResult;
        @Nonnull
        private final Function<byte[], RecordCursorProto.ConcatContinuation> continuationFunction;
        private final boolean isEnd;
        @Nullable
        private byte[] cachedBytes;

        private ConcatCursorContinuation(boolean secondCursor, @Nonnull RecordCursorResult<T> nextResult) {
            this.nextResult = nextResult;
            cachedBytes = null;
            isEnd = secondCursor && nextResult.getContinuation().isEnd() ? true : false;
            //defer forming bytes until requested
            continuationFunction = b -> RecordCursorProto.ConcatContinuation.newBuilder().setSecond(secondCursor).setContinuation(ByteString.copyFrom(b)).build();
        }

        @Nullable
        @Override
        public byte[] toBytes() {
            if (isEnd()) {
                return null;
            } else {
                //form bytes exactly once
                if (cachedBytes == null) {
                    cachedBytes = continuationFunction.apply(nextResult.getContinuation().toBytes()).toByteArray();
                }
                return cachedBytes;
            }
        }

        @Override
        public boolean isEnd() {
            return isEnd;
        }
    }
}
