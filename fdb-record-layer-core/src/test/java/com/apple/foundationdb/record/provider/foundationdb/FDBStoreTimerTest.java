/*
 * FDBStoreTimerTest.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.provider.foundationdb;

import com.apple.foundationdb.KeyValue;
import com.apple.foundationdb.record.ExecuteProperties;
import com.apple.foundationdb.record.RecordCoreArgumentException;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.TupleRange;
import com.apple.foundationdb.record.provider.common.StoreTimer;
import com.apple.foundationdb.record.provider.common.StoreTimerSnapshot;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.KeySpacePath;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.test.Tags;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FDBStoreTimer}.
 */
@Tag(Tags.RequiresFDB)
public class FDBStoreTimerTest {
    FDBDatabase fdb;
    FDBRecordContext context;
    private Subspace subspace;
    ExecuteProperties ep;

    @BeforeEach
    public void setup() throws Exception {
        fdb = FDBDatabaseFactory.instance().getDatabase();
        context = fdb.openContext();
        setupBaseData();
        FDBStoreTimer timer = new FDBStoreTimer();
        context.setTimer(timer);
    }

    @AfterEach
    public void teardown() throws Exception {
        context.close();
        fdb.close();
    }

    @Test
    public void counterDifferenceTest() {
        RecordCursor<KeyValue> kvc = KeyValueCursor.Builder.withSubspace(subspace).setContext(context).setScanProperties(ScanProperties.FORWARD_SCAN).setRange(TupleRange.ALL).build();

        // see the timer counts from some onNext calls
        FDBStoreTimer latestTimer = context.getTimer();
        StoreTimerSnapshot savedTimer;
        StoreTimer diffTimer;

        // get a snapshot from latestTimer before advancing cursor
        savedTimer = StoreTimerSnapshot.from(latestTimer);

        // advance the cursor once
        kvc.onNext().join();

        // the diff from latestTimer minus savedTimer will have the timer cost from the single cursor advance
        diffTimer = StoreTimer.getDifference(latestTimer, savedTimer);
        Map<String, Number> diffKVs;
        diffKVs = diffTimer.getKeysAndValues();
        assertThat(diffKVs, hasKey("load_scan_entry_count"));
        assertEquals(1, diffKVs.get("load_scan_entry_count").intValue());
        assertThat(diffKVs, hasKey("load_key_value_count"));
        assertEquals(1, diffKVs.get("load_key_value_count").intValue());

        // get a snapshot from latestTimer after the single cursor advance
        savedTimer = StoreTimerSnapshot.from(latestTimer);

        // advance the cursor more times
        final int numAdvances = 5;
        for (int i = 0; i < numAdvances; i++) {
            kvc.onNext().join();
        }

        // the diff from latestTimer and savedTimer will have the timer cost from the subsequent cursor advances
        diffTimer = StoreTimer.getDifference(latestTimer, savedTimer);
        diffKVs = diffTimer.getKeysAndValues();
        assertThat(diffKVs, hasKey("load_scan_entry_count"));
        assertEquals(numAdvances, diffKVs.get("load_scan_entry_count").intValue());
        assertThat(diffKVs, hasKey("load_key_value_count"));
        assertEquals(numAdvances, diffKVs.get("load_key_value_count").intValue(), numAdvances);
    }

    @Test
    public void timeoutCounterDifferenceTest() {
        RecordCursor<KeyValue> kvc = KeyValueCursor.Builder.withSubspace(subspace).setContext(context).setScanProperties(ScanProperties.FORWARD_SCAN).setRange(TupleRange.ALL).build();

        FDBStoreTimer latestTimer = context.getTimer();
        CompletableFuture<RecordCursorResult<KeyValue>> fkvr;
        RecordCursorResult<KeyValue> kvr;

        // record timeout
        latestTimer.recordTimeout(FDBStoreTimer.Waits.WAIT_ADVANCE_CURSOR, System.nanoTime() - 5000);

        // the latest timer should have recorded the one timeout event
        Map<String, Number> diffKVs;
        diffKVs = latestTimer.getKeysAndValues();
        assertEquals(1, diffKVs.get("wait_advance_cursor_timeout_count").intValue());
        assertTrue(diffKVs.get("wait_advance_cursor_timeout_micros").intValue() > 0);
        assertThat(diffKVs.get("wait_advance_cursor_timeout_micros").intValue(), greaterThan(0));

        // advance the cursor without timing out
        latestTimer.record(FDBStoreTimer.Waits.WAIT_ADVANCE_CURSOR, System.nanoTime());
        latestTimer.record(FDBStoreTimer.Waits.WAIT_ADVANCE_CURSOR, System.nanoTime());

        // record the state after the first timeout event and generate some more timeout events
        StoreTimerSnapshot savedTimer;
        savedTimer = StoreTimerSnapshot.from(latestTimer);
        final int numTimeouts = 3;
        for (int i = 0; i < numTimeouts; i++) {
            latestTimer.recordTimeout(FDBStoreTimer.Waits.WAIT_ADVANCE_CURSOR, System.nanoTime() - 5000);
        }

        // should have the additional timeout events in latestTimer
        diffKVs = latestTimer.getKeysAndValues();
        assertEquals(numTimeouts + 1, diffKVs.get("wait_advance_cursor_timeout_count").intValue());
        assertThat(diffKVs.get("wait_advance_cursor_timeout_micros").intValue(), greaterThan(0));

        // the savedTimer should only have recorded the first timeout event and hence the difference is the numTimeout events that occurred after that
        StoreTimer diffTimer;
        diffTimer = StoreTimer.getDifference(latestTimer, savedTimer);
        diffKVs = diffTimer.getKeysAndValues();
        assertEquals(numTimeouts, diffKVs.get("wait_advance_cursor_timeout_count").intValue());
        assertThat(diffKVs.get("wait_advance_cursor_timeout_micros").intValue(), greaterThan(0));
    }

    @Test
    public void timerConstraintChecks() {

        // invalid to substract a snapshot timer from a timer that has been reset after the snapshot was taken
        FDBStoreTimer latestTimer = context.getTimer();
        final StoreTimerSnapshot savedTimer;
        savedTimer = StoreTimerSnapshot.from(latestTimer);
        latestTimer.reset();
        assertThrows(RecordCoreArgumentException.class, () -> StoreTimer.getDifference(latestTimer, savedTimer));

        // invalid to subtract a snapshot timer from a timer it was not derived from
        StoreTimer anotherStoreTimer = new StoreTimer();
        assertThrows(RecordCoreArgumentException.class, () -> StoreTimer.getDifference(anotherStoreTimer, savedTimer));
    }

    @Test
    public void unchangedMetricsExcludedFromSnapshotDifference() {
        StoreTimer timer = new FDBStoreTimer();

        timer.increment(FDBStoreTimer.Counts.CREATE_RECORD_STORE);
        timer.increment(FDBStoreTimer.Counts.DELETE_RECORD_KEY);
        timer.record(FDBStoreTimer.Events.CHECK_VERSION, 1L);
        timer.record(FDBStoreTimer.Events.DIRECTORY_READ, 3L);

        StoreTimerSnapshot snapshot = StoreTimerSnapshot.from(timer);

        timer.increment(FDBStoreTimer.Counts.DELETE_RECORD_KEY);
        timer.record(FDBStoreTimer.Events.DIRECTORY_READ, 7L);

        StoreTimer diff = StoreTimer.getDifference(timer, snapshot);
        assertThat(diff.getCounter(FDBStoreTimer.Counts.CREATE_RECORD_STORE), Matchers.nullValue());
        assertThat(diff.getCounter(FDBStoreTimer.Events.CHECK_VERSION), Matchers.nullValue());
        assertThat(diff.getCounter(FDBStoreTimer.Counts.DELETE_RECORD_KEY).getCount(), Matchers.is(1));
        assertThat(diff.getCounter(FDBStoreTimer.Counts.DELETE_RECORD_KEY).getTimeNanos(), Matchers.is(0L));
        assertThat(diff.getCounter(FDBStoreTimer.Events.DIRECTORY_READ).getCount(), Matchers.is(1));
        assertThat(diff.getCounter(FDBStoreTimer.Events.DIRECTORY_READ).getTimeNanos(), Matchers.is(7L));
    }

    @Test
    public void newMetricsAddedToSnapshotDifference() {
        StoreTimer timer = new FDBStoreTimer();

        timer.increment(FDBStoreTimer.Counts.DELETE_RECORD_KEY);

        StoreTimerSnapshot snapshot = StoreTimerSnapshot.from(timer);

        timer.increment(FDBStoreTimer.Counts.DELETE_RECORD_KEY);
        timer.record(FDBStoreTimer.Events.DIRECTORY_READ, 7L);

        StoreTimer diff = StoreTimer.getDifference(timer, snapshot);
        assertThat(diff.getCounter(FDBStoreTimer.Counts.DELETE_RECORD_KEY).getCount(), Matchers.is(1));
        assertThat(diff.getCounter(FDBStoreTimer.Counts.DELETE_RECORD_KEY).getTimeNanos(), Matchers.is(0L));
        assertThat(diff.getCounter(FDBStoreTimer.Events.DIRECTORY_READ).getCount(), Matchers.is(1));
        assertThat(diff.getCounter(FDBStoreTimer.Events.DIRECTORY_READ).getTimeNanos(), Matchers.is(7L));
    }

    private enum TestEvent implements StoreTimer.Event {

        EVENT_WITH_LONG_NAME("An event with a very long name", "ShorterName"),
        EVENT_WITH_SHORT_NAME("An event with a very short name", null);

        private final String title;
        private final String logKey;

        TestEvent(@Nonnull String title, String logKey) {
            this.title = title;
            this.logKey = (logKey != null) ? logKey : StoreTimer.Event.super.logKey();
        }
        
        @Override
        public String title() {
            return this.title;
        }

        @Override
        public String logKey() {
            return this.logKey;
        }
    }

    @Test
    public void logKeyTest() {
        // If log key has not been specified, 'logKey()' should return then '.name()' of the enum.
        assertEquals(TestEvent.EVENT_WITH_SHORT_NAME.logKey(), "event_with_short_name");

        // If log key has been specified, 'logKey()' should return the specified log key.
        assertEquals(TestEvent.EVENT_WITH_LONG_NAME.logKey(), "ShorterName");
    }

    @Test
    public void testAggregateMetrics() {
        FDBStoreTimer storeTimer = new FDBStoreTimer();

        // I don't want this test to fail if new aggregates are added, but do want to verify that the
        // getAggregates() at least does return some of the expected aggregates.
        assertTrue(storeTimer.getAggregates().contains(FDBStoreTimer.EventAggregates.COMMITS));
        assertTrue(storeTimer.getAggregates().contains(FDBStoreTimer.CountAggregates.READS));
        assertTrue(storeTimer.getAggregates().contains(FDBStoreTimer.CountAggregates.BYTES_READ));
        assertTrue(storeTimer.getAggregates().contains(FDBStoreTimer.CountAggregates.WRITES));
        assertTrue(storeTimer.getAggregates().contains(FDBStoreTimer.CountAggregates.BYTES_WRITTEN));

        storeTimer.increment(FDBStoreTimer.Counts.SAVE_RECORD_KEY, 2);
        storeTimer.increment(FDBStoreTimer.Counts.SAVE_RECORD_KEY_BYTES, 20);
        storeTimer.increment(FDBStoreTimer.Counts.SAVE_RECORD_VALUE_BYTES, 20);
        storeTimer.increment(FDBStoreTimer.Counts.SAVE_RECORD_KEY_BYTES, 11);
        storeTimer.increment(FDBStoreTimer.Counts.SAVE_RECORD_VALUE_BYTES, 97);
        storeTimer.increment(FDBStoreTimer.Counts.SAVE_INDEX_KEY, 4);
        storeTimer.increment(FDBStoreTimer.Counts.SAVE_INDEX_KEY_BYTES, 44);
        storeTimer.increment(FDBStoreTimer.Counts.SAVE_INDEX_VALUE_BYTES, 287);

        assertNotNull(storeTimer.getCounter(FDBStoreTimer.CountAggregates.WRITES));
        assertEquals(6, storeTimer.getCount(FDBStoreTimer.CountAggregates.WRITES),
                "Incorrect aggregate count for WRITES");
        assertNotNull(storeTimer.getCounter(FDBStoreTimer.CountAggregates.BYTES_WRITTEN));
        assertEquals(479, storeTimer.getCount(FDBStoreTimer.CountAggregates.BYTES_WRITTEN),
                "Incorrect aggregate count for BYTES_WRITTEN");
        assertEquals(0, storeTimer.getTimeNanos(FDBStoreTimer.CountAggregates.WRITES),
                "Incorrect aggregate time for WRITES");
        assertEquals(0, storeTimer.getTimeNanos(FDBStoreTimer.CountAggregates.BYTES_WRITTEN),
                "Incorrect aggregate time for BYTES_WRITTEN");
        assertNull(storeTimer.getCounter(FDBStoreTimer.CountAggregates.READS));
        assertEquals(0, storeTimer.getCount(FDBStoreTimer.CountAggregates.READS),
                "READS should be zero");
        assertNull(storeTimer.getCounter(FDBStoreTimer.CountAggregates.BYTES_READ));
        assertEquals(0, storeTimer.getCount(FDBStoreTimer.CountAggregates.BYTES_READ),
                "BYTES_READ should be zero");

        storeTimer.record(FDBStoreTimer.Events.COMMIT, 2_300_000L);
        storeTimer.record(FDBStoreTimer.Events.COMMIT, 1_001_726L);
        storeTimer.record(FDBStoreTimer.Events.COMMIT_FAILURE, 312_423L);

        assertNotNull(storeTimer.getCounter(FDBStoreTimer.EventAggregates.COMMITS));
        assertEquals(3_614_149L, storeTimer.getTimeNanos(FDBStoreTimer.EventAggregates.COMMITS),
                "Incorrect aggregate time for COMMITS");
        assertEquals(3, storeTimer.getCount(FDBStoreTimer.EventAggregates.COMMITS),
                "Incorrect aggregate count for COMMITS");

        // Aggregate counters are immutable.
        assertThrows(RecordCoreException.class, () -> {
            storeTimer.getCounter(FDBStoreTimer.EventAggregates.COMMITS).increment(44);
        });
    }

    private void setupBaseData() {
        subspace = fdb.run(context -> {
            KeySpacePath path = TestKeySpace.getKeyspacePath("record-test", "unit");
            FDBRecordStore.deleteStore(context, path);
            return path.toSubspace(context);
        });

        // Populate with data.
        fdb.database().run(tr -> {
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 5; j++) {
                    tr.set(subspace.pack(Tuple.from(i, j)), Tuple.from(i, j).pack());
                }
            }
            return null;
        });
    }
}
