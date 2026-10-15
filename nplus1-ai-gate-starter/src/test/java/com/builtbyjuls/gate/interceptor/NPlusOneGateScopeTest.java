package com.builtbyjuls.gate.interceptor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NPlusOneGateScopeTest {

    @BeforeEach
    @AfterEach
    void cleanScope() {
        NPlusOneGateScope.reset();
        NPlusOneGateScope.setDiagnosticMode(false);
    }

    @Test
    @DisplayName("Should record queries and return immutable list")
    void shouldRecordQueriesAndReturnImmutableList() {
        RecordedQuery query1 = new RecordedQuery("SELECT 1", List.of(), List.of(), false);
        RecordedQuery query2 = new RecordedQuery("SELECT 2", List.of(), List.of(), false);

        NPlusOneGateScope.record(query1);
        NPlusOneGateScope.record(query2);

        List<RecordedQuery> recorded = NPlusOneGateScope.getRecordedQueries();
        assertThat(recorded).containsExactly(query1, query2);

        assertThatThrownBy(() -> recorded.add(query1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should clear queries on reset()")
    void shouldClearQueriesOnReset() {
        NPlusOneGateScope.record(new RecordedQuery("SELECT 1", List.of(), List.of(), false));
        assertThat(NPlusOneGateScope.getRecordedQueries()).hasSize(1);

        NPlusOneGateScope.reset();
        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();
    }

    @Test
    @DisplayName("measure(Runnable) should clear queries before executing action")
    void measureRunnableShouldClearPriorQueries() {
        NPlusOneGateScope.record(new RecordedQuery("SELECT prior", List.of(), List.of(), false));

        NPlusOneGateScope.measure(() -> {
            NPlusOneGateScope.record(new RecordedQuery("SELECT during", List.of(), List.of(), false));
        });

        assertThat(NPlusOneGateScope.getRecordedQueries())
                .extracting(RecordedQuery::rawSql)
                .containsExactly("SELECT during");
    }

    @Test
    @DisplayName("measure(Supplier) should clear queries and return supplier result")
    void measureSupplierShouldClearPriorQueriesAndReturnResult() {
        NPlusOneGateScope.record(new RecordedQuery("SELECT prior", List.of(), List.of(), false));

        String result = NPlusOneGateScope.measure(() -> {
            NPlusOneGateScope.record(new RecordedQuery("SELECT measured", List.of(), List.of(), false));
            return "SUCCESS";
        });

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(NPlusOneGateScope.getRecordedQueries())
                .extracting(RecordedQuery::rawSql)
                .containsExactly("SELECT measured");
    }

    @Test
    @DisplayName("Diagnostic mode should suppress recording")
    void diagnosticModeShouldSuppressRecording() {
        NPlusOneGateScope.setDiagnosticMode(true);
        NPlusOneGateScope.record(new RecordedQuery("EXPLAIN SELECT 1", List.of(), List.of(), false));

        assertThat(NPlusOneGateScope.getRecordedQueries()).isEmpty();

        NPlusOneGateScope.setDiagnosticMode(false);
        NPlusOneGateScope.record(new RecordedQuery("SELECT 2", List.of(), List.of(), false));

        assertThat(NPlusOneGateScope.getRecordedQueries()).hasSize(1);
    }

    @Test
    @DisplayName("withDiagnosticMode should temporarily suppress and restore state")
    void withDiagnosticModeShouldTemporarilySuppressAndRestore() {
        NPlusOneGateScope.record(new RecordedQuery("SELECT before", List.of(), List.of(), false));

        NPlusOneGateScope.withDiagnosticMode(() -> {
            assertThat(NPlusOneGateScope.isInDiagnosticMode()).isTrue();
            NPlusOneGateScope.record(new RecordedQuery("EXPLAIN (FORMAT JSON) ...", List.of(), List.of(), false));
        });

        assertThat(NPlusOneGateScope.isInDiagnosticMode()).isFalse();

        NPlusOneGateScope.record(new RecordedQuery("SELECT after", List.of(), List.of(), false));

        assertThat(NPlusOneGateScope.getRecordedQueries())
                .extracting(RecordedQuery::rawSql)
                .containsExactly("SELECT before", "SELECT after");
    }

    @Test
    @DisplayName("withDiagnosticMode(Supplier) should temporarily suppress and return result")
    void withDiagnosticModeSupplierShouldTemporarilySuppressAndReturnResult() {
        NPlusOneGateScope.record(new RecordedQuery("SELECT before", List.of(), List.of(), false));

        String planResult = NPlusOneGateScope.withDiagnosticMode(() -> {
            assertThat(NPlusOneGateScope.isInDiagnosticMode()).isTrue();
            NPlusOneGateScope.record(new RecordedQuery("EXPLAIN (FORMAT JSON) ...", List.of(), List.of(), false));
            return "[{\"Node Type\": \"Index Scan\"}]";
        });

        assertThat(planResult).isEqualTo("[{\"Node Type\": \"Index Scan\"}]");
        assertThat(NPlusOneGateScope.isInDiagnosticMode()).isFalse();

        NPlusOneGateScope.record(new RecordedQuery("SELECT after", List.of(), List.of(), false));

        assertThat(NPlusOneGateScope.getRecordedQueries())
                .extracting(RecordedQuery::rawSql)
                .containsExactly("SELECT before", "SELECT after");
    }

    @Test
    @DisplayName("Should capture queries concurrently from Java 21 Virtual Threads")
    void shouldCaptureQueriesConcurrentlyFromVirtualThreads() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            Thread thread = Thread.ofVirtual().start(() -> {
                try {
                    NPlusOneGateScope.record(new RecordedQuery("SELECT vthread_" + index, List.of(), List.of(), false));
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        for (Thread t : threads) {
            t.join();
        }

        assertThat(NPlusOneGateScope.getRecordedQueries()).hasSize(threadCount);
    }

    @Test
    @DisplayName("Should capture queries concurrently across parallelStream()")
    void shouldCaptureQueriesConcurrentlyAcrossParallelStream() {
        int streamCount = 50;

        IntStream.range(0, streamCount)
                .parallel()
                .forEach(i -> NPlusOneGateScope.record(
                        new RecordedQuery("SELECT pstream_" + i, List.of(), List.of(), false)
                ));

        assertThat(NPlusOneGateScope.getRecordedQueries()).hasSize(streamCount);
    }
}
