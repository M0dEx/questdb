/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.table;

import io.questdb.cairo.*;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.*;
import io.questdb.cairo.sql.async.PageFrameReduceTask;
import io.questdb.cairo.sql.async.PageFrameSequence;
import io.questdb.griffin.*;
import io.questdb.griffin.engine.analytic.AnalyticContext;
import io.questdb.jit.JitUtil;
import io.questdb.mp.*;
import io.questdb.std.Misc;
import io.questdb.std.Rnd;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.questdb.cairo.sql.DataFrameCursorFactory.ORDER_ANY;

public class AsyncFilteredRecordCursorFactoryTest extends AbstractGriffinTest {

    private static final int QUEUE_CAPACITY = 4;

    @BeforeClass
    public static void setUpStatic() {
        // Having a single shard is important for many tests in this suite. See resetTaskCapacities
        // method for more detail.
        pageFrameReduceShardCount = 1;
        // We intentionally use a small capacity for the reduce queue to exhibit various edge cases.
        pageFrameReduceQueueCapacity = QUEUE_CAPACITY;

        AbstractGriffinTest.setUpStatic();
    }

    @Test
    public void testNoLimit() throws Exception {
        testNoLimit(true, SqlJitMode.JIT_MODE_DISABLED, AsyncFilteredRecordCursorFactory.class);
    }

    @Test
    public void testNoLimitJit() throws Exception {
        // Disable the test on ARM64.
        Assume.assumeTrue(JitUtil.isJitSupported());
        testNoLimit(true, SqlJitMode.JIT_MODE_ENABLED, AsyncJitFilteredRecordCursorFactory.class);
    }

    @Test
    public void testNoLimitDisabledParallelFilterJit() throws Exception {
        // Disable the test on ARM64.
        Assume.assumeTrue(JitUtil.isJitSupported());
        // JIT should be ignored since it's only supported for parallel filters.
        testNoLimit(false, SqlJitMode.JIT_MODE_ENABLED, FilteredRecordCursorFactory.class);
    }

    @Test
    public void testNoLimitDisabledParallelFilterNonJit() throws Exception {
        testNoLimit(false, SqlJitMode.JIT_MODE_DISABLED, FilteredRecordCursorFactory.class);
    }

    @Test
    public void testSymbolEqualsBindVariableFilter() throws Exception {
        testSymbolEqualsBindVariableFilter(SqlJitMode.JIT_MODE_DISABLED, AsyncFilteredRecordCursorFactory.class);
    }

    @Test
    public void testSymbolEqualsBindVariableFilterJit() throws Exception {
        // Disable the test on ARM64.
        Assume.assumeTrue(JitUtil.isJitSupported());
        testSymbolEqualsBindVariableFilter(SqlJitMode.JIT_MODE_ENABLED, AsyncJitFilteredRecordCursorFactory.class);
    }

    @Test
    public void testSymbolRegexBindVariableFilter() throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            // JIT compiler doesn't support ~ operator for symbols.
            sqlExecutionContext.setJitMode(SqlJitMode.JIT_MODE_DISABLED);
            compiler.compile("create table x as (select rnd_symbol('A','B','C') s, timestamp_sequence(20000000, 100000) t from long_sequence(500000)) timestamp(t) partition by hour", sqlExecutionContext);

            final String sql = "select * from x where s ~ $1 limit 10";
            try (RecordCursorFactory f = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, f.getClass());
            }

            bindVariableService.clear();
            bindVariableService.setStr(0, "C");

            assertQuery(
                    compiler,
                    "s\tt\n" +
                            "C\t1970-01-01T00:00:20.300000Z\n" +
                            "C\t1970-01-01T00:00:20.400000Z\n" +
                            "C\t1970-01-01T00:00:20.500000Z\n" +
                            "C\t1970-01-01T00:00:20.600000Z\n" +
                            "C\t1970-01-01T00:00:21.100000Z\n" +
                            "C\t1970-01-01T00:00:22.300000Z\n" +
                            "C\t1970-01-01T00:00:22.600000Z\n" +
                            "C\t1970-01-01T00:00:23.000000Z\n" +
                            "C\t1970-01-01T00:00:23.200000Z\n" +
                            "C\t1970-01-01T00:00:23.300000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    false
            );

            resetTaskCapacities();
        });
    }

    @Test
    public void testDeferredSymbolInFilter() throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            // JIT compiler doesn't support IN operator for symbols.
            sqlExecutionContext.setJitMode(SqlJitMode.JIT_MODE_DISABLED);
            compiler.compile("create table x as (select rnd_symbol('A','B') s, timestamp_sequence(20000000, 100000) t from long_sequence(500000)) timestamp(t) partition by hour", sqlExecutionContext);

            final String sql = "select * from x where s in ('C','D') limit 10";
            try (final RecordCursorFactory factory = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, factory.getClass());

                assertCursor(
                        "s\tt\n",
                        factory,
                        true,
                        true,
                        false,
                        false,
                        sqlExecutionContext
                );

                compiler.compile("insert into x select rnd_symbol('C','D') s, timestamp_sequence(100000000000, 100000) from long_sequence(100)", sqlExecutionContext);

                // Verify that all symbol tables (original and views) are refreshed to include the new symbols.
                assertCursor(
                        "s\tt\n" +
                                "C\t1970-01-02T03:46:40.000000Z\n" +
                                "C\t1970-01-02T03:46:40.100000Z\n" +
                                "D\t1970-01-02T03:46:40.200000Z\n" +
                                "C\t1970-01-02T03:46:40.300000Z\n" +
                                "D\t1970-01-02T03:46:40.400000Z\n" +
                                "C\t1970-01-02T03:46:40.500000Z\n" +
                                "D\t1970-01-02T03:46:40.600000Z\n" +
                                "D\t1970-01-02T03:46:40.700000Z\n" +
                                "C\t1970-01-02T03:46:40.800000Z\n" +
                                "D\t1970-01-02T03:46:40.900000Z\n",
                        factory,
                        true,
                        true,
                        false,
                        false,
                        sqlExecutionContext
                );
            }

            resetTaskCapacities();
        });
    }

    @Test
    public void testPositiveLimit() throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            sqlExecutionContext.setJitMode(SqlJitMode.JIT_MODE_DISABLED);
            compiler.compile("create table x as (select rnd_double() a, timestamp_sequence(20000000, 100000) t from long_sequence(2000000)) timestamp(t) partition by hour", sqlExecutionContext);
            final String sql = "x where a > 0.345747032 and a < 0.34575 limit 5";
            try (RecordCursorFactory f = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, f.getClass());
            }

            assertQuery(compiler,
                    "a\tt\n" +
                            "0.34574819315105954\t1970-01-01T15:03:20.500000Z\n" +
                            "0.34574734261660356\t1970-01-02T02:14:37.600000Z\n" +
                            "0.34574784156471083\t1970-01-02T08:17:06.600000Z\n" +
                            "0.34574958643398823\t1970-01-02T20:31:57.900000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    false
            );
        });
    }

    @Test
    public void testPositiveLimitGroupBy() throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            compiler.compile("create table x as (select rnd_double() a, timestamp_sequence(20000000, 100000) t from long_sequence(2000000)) timestamp(t) partition by hour", sqlExecutionContext);
            final String sql = "select sum(a) from x where a > 0.345747032 and a < 0.34575 limit 5";
            try (RecordCursorFactory f = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(io.questdb.griffin.engine.LimitRecordCursorFactory.class, f.getClass());
            }

            assertQuery(compiler,
                    "sum\n" +
                            "1.382992963766362\n",
                    sql,
                    null,
                    false,
                    sqlExecutionContext,
                    true
            );
        });
    }

    @Test
    public void testLimitBinVariable() throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            compiler.compile("create table x as (select rnd_double() a, timestamp_sequence(20000000, 100000) t from long_sequence(2000000)) timestamp(t) partition by hour", sqlExecutionContext);
            final String sql = "x where a > 0.345747032 and a < 0.34575 limit $1";
            try (RecordCursorFactory f = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, f.getClass());
            }

            sqlExecutionContext.getBindVariableService().setLong(0, 3);
            assertQuery(compiler,
                    "a\tt\n" +
                            "0.34574819315105954\t1970-01-01T15:03:20.500000Z\n" +
                            "0.34574734261660356\t1970-01-02T02:14:37.600000Z\n" +
                            "0.34574784156471083\t1970-01-02T08:17:06.600000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    false
            );

            // greater
            sqlExecutionContext.getBindVariableService().setLong(0, 5);
            assertQuery(compiler,
                    "a\tt\n" +
                            "0.34574819315105954\t1970-01-01T15:03:20.500000Z\n" +
                            "0.34574734261660356\t1970-01-02T02:14:37.600000Z\n" +
                            "0.34574784156471083\t1970-01-02T08:17:06.600000Z\n" +
                            "0.34574958643398823\t1970-01-02T20:31:57.900000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    false
            );

            // lower
            sqlExecutionContext.getBindVariableService().setLong(0, 2);
            assertQuery(compiler,
                    "a\tt\n" +
                            "0.34574819315105954\t1970-01-01T15:03:20.500000Z\n" +
                            "0.34574734261660356\t1970-01-02T02:14:37.600000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    false
            );

            // negative
            sqlExecutionContext.getBindVariableService().setLong(0, -2);
            assertQuery(compiler,
                    "a\tt\n" +
                            "0.34574784156471083\t1970-01-02T08:17:06.600000Z\n" +
                            "0.34574958643398823\t1970-01-02T20:31:57.900000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    true // cursor for negative limit accumulates row ids, so it supports size
            );

            resetTaskCapacities();
        });
    }

    @Test
    public void testNegativeLimit() throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            compiler.compile("create table x as (select rnd_double() a, timestamp_sequence(20000000, 100000) t from long_sequence(2000000)) timestamp(t) partition by hour", sqlExecutionContext);
            final String sql = "x where a > 0.345747032 and a < 0.34575 limit -5";
            try (RecordCursorFactory f = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, f.getClass());
            }

            assertQuery(compiler,
                    "a\tt\n" +
                            "0.34574819315105954\t1970-01-01T15:03:20.500000Z\n" +
                            "0.34574734261660356\t1970-01-02T02:14:37.600000Z\n" +
                            "0.34574784156471083\t1970-01-02T08:17:06.600000Z\n" +
                            "0.34574958643398823\t1970-01-02T20:31:57.900000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    true
            );

            resetTaskCapacities();
        });
    }

    @Test
    public void testPageFrameSequenceJit() throws Exception {
        // Disable the test on ARM64.
        Assume.assumeTrue(JitUtil.isJitSupported());
        testPageFrameSequence(SqlJitMode.JIT_MODE_ENABLED, AsyncJitFilteredRecordCursorFactory.class);
    }

    @Test
    public void testPageFrameSequenceNonJit() throws Exception {
        testPageFrameSequence(SqlJitMode.JIT_MODE_DISABLED, AsyncFilteredRecordCursorFactory.class);
    }

    @Test
    public void testFullQueueNoLimit() throws Exception {
        testFullQueue("x where a > 0.42");
    }

    @Test
    public void testFullQueuePositiveLimit() throws Exception {
        testFullQueue("x where a > 0.42 limit 3");
    }

    @Test
    public void testFullQueueNegativeLimit() throws Exception {
        testFullQueue("x where a > 0.42 limit -3");
    }

    private void testNoLimit(boolean enableParallelFilter, int jitMode, Class<?> expectedFactoryClass) throws Exception {
        AbstractCairoTest.enableParallelFilter = enableParallelFilter;
        withPool((engine, compiler, sqlExecutionContext) -> {
            sqlExecutionContext.setJitMode(jitMode);
            compiler.compile("create table x as (select rnd_double() a, timestamp_sequence(20000000, 100000) t from long_sequence(2000000)) timestamp(t) partition by hour", sqlExecutionContext);
            final String sql = "x where a > 0.345747032 and a < 0.34575";
            try (RecordCursorFactory f = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(expectedFactoryClass, f.getClass());
            }

            assertQuery(
                    compiler,
                    "a\tt\n" +
                            "0.34574819315105954\t1970-01-01T15:03:20.500000Z\n" +
                            "0.34574734261660356\t1970-01-02T02:14:37.600000Z\n" +
                            "0.34574784156471083\t1970-01-02T08:17:06.600000Z\n" +
                            "0.34574958643398823\t1970-01-02T20:31:57.900000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    false
            );
        });
    }

    private void testSymbolEqualsBindVariableFilter(int jitMode, Class<?> expectedFactoryClass) throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            sqlExecutionContext.setJitMode(jitMode);
            compiler.compile("create table x as (select rnd_symbol('A','B','C') s, timestamp_sequence(20000000, 100000) t from long_sequence(500000)) timestamp(t) partition by hour", sqlExecutionContext);

            final String sql = "select * from x where s = $1 limit 10";
            try (RecordCursorFactory f = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
                Assert.assertEquals(expectedFactoryClass, f.getClass());
            }

            bindVariableService.clear();
            bindVariableService.setStr(0, "C");

            assertQuery(
                    compiler,
                    "s\tt\n" +
                            "C\t1970-01-01T00:00:20.300000Z\n" +
                            "C\t1970-01-01T00:00:20.400000Z\n" +
                            "C\t1970-01-01T00:00:20.500000Z\n" +
                            "C\t1970-01-01T00:00:20.600000Z\n" +
                            "C\t1970-01-01T00:00:21.100000Z\n" +
                            "C\t1970-01-01T00:00:22.300000Z\n" +
                            "C\t1970-01-01T00:00:22.600000Z\n" +
                            "C\t1970-01-01T00:00:23.000000Z\n" +
                            "C\t1970-01-01T00:00:23.200000Z\n" +
                            "C\t1970-01-01T00:00:23.300000Z\n",
                    sql,
                    "t",
                    true,
                    sqlExecutionContext,
                    false
            );

            resetTaskCapacities();
        });
    }

    private void testPageFrameSequence(int jitMode, Class<?> expectedFactoryClass) throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> {
            sqlExecutionContext.setJitMode(jitMode);

            compiler.compile("create table x as (select rnd_double() a, timestamp_sequence(20000000, 100000) t from long_sequence(2000000)) timestamp(t) partition by hour", sqlExecutionContext);
            try (RecordCursorFactory f = compiler.compile("x where a > 0.34", sqlExecutionContext).getRecordCursorFactory()) {

                Assert.assertEquals(expectedFactoryClass, f.getClass());
                SCSequence subSeq = new SCSequence();
                PageFrameSequence<?> frameSequence = f.execute(sqlExecutionContext, subSeq, ORDER_ANY);

                int frameCount = 0;
                while (frameCount < frameSequence.getFrameCount()) {
                    long cursor = frameSequence.next();
                    if (cursor < 0) {
                        continue;
                    }
                    PageFrameReduceTask task = frameSequence.getTask(cursor);
                    PageFrameSequence<?> taskSequence = task.getFrameSequence();
                    Assert.assertEquals(frameSequence, taskSequence);
                    frameCount++;
                    frameSequence.collect(cursor, false);
                }
                frameSequence.await();
                Misc.free(frameSequence.getSymbolTableSource());
                frameSequence.clear();
            }
        });
    }

    private void testFullQueue(String query) throws Exception {
        final int pageFrameRows = 100;
        pageFrameMaxRows = pageFrameRows;

        withPool((engine, compiler, sqlExecutionContext) -> {
            sqlExecutionContext.setJitMode(SqlJitMode.JIT_MODE_DISABLED);
            compiler.compile("create table x as (" +
                    "  select rnd_double() a," +
                    "  timestamp_sequence(0, 100000) t from long_sequence(" + (10 * pageFrameRows * QUEUE_CAPACITY) + ")" +
                    ") timestamp(t) partition by hour", sqlExecutionContext);

            try (
                    RecordCursorFactory f1 = compiler.compile(query, sqlExecutionContext).getRecordCursorFactory();
                    RecordCursorFactory f2 = compiler.compile(query, sqlExecutionContext).getRecordCursorFactory()
            ) {
                Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, f1.getClass());
                Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, f2.getClass());

                try (
                        RecordCursor c1 = f1.getCursor(sqlExecutionContext);
                        RecordCursor c2 = f2.getCursor(sqlExecutionContext)
                ) {
                    StringSink sink1 = new StringSink();
                    StringSink sink2 = new StringSink();

                    Record r1 = c1.getRecord();
                    Record r2 = c2.getRecord();

                    // We expect both cursors to be able to make progress even although only one of them
                    // occupies the reduce queue most of the time. The second one should be using a local task.
                    while (c1.hasNext()) {
                        printer.print(r1, f1.getMetadata(), sink1);
                        if (c2.hasNext()) {
                            printer.print(r2, f2.getMetadata(), sink2);
                        }
                    }

                    Assert.assertFalse(c1.hasNext());
                    Assert.assertFalse(c2.hasNext());

                    TestUtils.assertEquals(sink1, sink2);
                }
            }

            resetTaskCapacities();
        });
    }

    private void resetTaskCapacities() {
        // Tests that involve LIMIT clause may lead to only a fraction of the page frames being
        // reduced and/or collected before the factory gets closed. When that happens, row id and
        // column lists' capacities in the reduce queue's tasks don't get reset to initial values,
        // so the memory leak check fails. As a workaround, we clean up the memory manually.
        final long maxPageFrameRows = configuration.getSqlPageFrameMaxRows();
        final RingQueue<PageFrameReduceTask> tasks = engine.getMessageBus().getPageFrameReduceQueue(0);
        for (int i = 0; i < tasks.getCycle(); i++) {
            PageFrameReduceTask task = tasks.get(i);
            Assert.assertTrue("Row id list capacity exceeds max page frame rows", task.getRows().getCapacity() <= maxPageFrameRows);
            task.resetCapacities();
        }
    }

    private void withPool(CustomisableRunnable runnable) throws Exception {
        int workerCount = 4;
        assertMemoryLeak(() -> {

            WorkerPool pool = new TestWorkerPool(workerCount);

            pool.assignCleaner(Path.CLEANER);

            O3Utils.setupWorkerPool(
                    pool,
                    engine,
                    null,
                    null
            );
            pool.start(null);

            try {
                runnable.run(engine, compiler, new DelegatingSqlExecutionContext() {
                    @Override
                    public int getWorkerCount() {
                        return workerCount;
                    }
                });
            } catch (Throwable e) {
                e.printStackTrace();
                throw e;
            } finally {
                pool.halt();
            }
        });
    }

    private void withDoublePool(CustomisableRunnable runnable) throws Exception {
        final int sharedPoolWorkerCount = 1;
        final int stealingPoolWorkerCount = 4;
        final AtomicInteger errorCounter = new AtomicInteger();
        final Rnd rnd = new Rnd();

        assertMemoryLeak(() -> {
            final WorkerPool sharedPool = new TestWorkerPool(sharedPoolWorkerCount);

            sharedPool.assignCleaner(Path.CLEANER);

            O3Utils.setupWorkerPool(
                    sharedPool,
                    engine,
                    null,
                    null
            );
            sharedPool.start(null);

            final WorkerPool stealingPool = new TestWorkerPool(stealingPoolWorkerCount);

            stealingPool.assignCleaner(Path.CLEANER);

            SOCountDownLatch doneLatch = new SOCountDownLatch(1);

            stealingPool.assign(new SynchronizedJob() {
                boolean run = true;
                @Override
                protected boolean runSerially() {
                    if (run) {
                        try {
                            runnable.run(engine, compiler, new DelegatingSqlExecutionContext() {
                                @Override
                                public int getWorkerCount() {
                                    return sharedPoolWorkerCount;
                                }

                                @Override
                                public Rnd getRandom() {
                                    return rnd;
                                }
                            });
                        } catch (Throwable e) {
                            e.printStackTrace();
                            errorCounter.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                            run =false;
                        }
                        return true;
                    }
                    return false;
                }
            });

            stealingPool.start(null);

            try {
                doneLatch.await();
                Assert.assertEquals(0, errorCounter.get());
            } finally {
                sharedPool.halt();
                stealingPool.halt();
            }
        });
    }

    private static abstract class DelegatingSqlExecutionContext implements SqlExecutionContext {
        @Override
        public QueryFutureUpdateListener getQueryFutureUpdateListener() {
            return sqlExecutionContext.getQueryFutureUpdateListener();
        }

        @Override
        public BindVariableService getBindVariableService() {
            return sqlExecutionContext.getBindVariableService();
        }

        @Override
        public CairoSecurityContext getCairoSecurityContext() {
            return sqlExecutionContext.getCairoSecurityContext();
        }

        @Override
        public boolean isTimestampRequired() {
            return sqlExecutionContext.isTimestampRequired();
        }

        @Override
        public void popTimestampRequiredFlag() {
            sqlExecutionContext.popTimestampRequiredFlag();
        }

        @Override
        public void pushTimestampRequiredFlag(boolean flag) {
            sqlExecutionContext.pushTimestampRequiredFlag(flag);
        }

        @Override
        public Rnd getRandom() {
            return sqlExecutionContext.getRandom();
        }

        @Override
        public void setRandom(Rnd rnd) {
            sqlExecutionContext.setRandom(rnd);
        }

        @Override
        public CairoEngine getCairoEngine() {
            return sqlExecutionContext.getCairoEngine();
        }

        @Override
        public long getRequestFd() {
            return sqlExecutionContext.getRequestFd();
        }

        @Override
        public SqlExecutionCircuitBreaker getCircuitBreaker() {
            return sqlExecutionContext.getCircuitBreaker();
        }

        @Override
        public void storeTelemetry(short event, short origin) {
            sqlExecutionContext.storeTelemetry(event, origin);
        }

        @Override
        public AnalyticContext getAnalyticContext() {
            return sqlExecutionContext.getAnalyticContext();
        }

        @Override
        public void configureAnalyticContext(
                @Nullable VirtualRecord partitionByRecord,
                @Nullable RecordSink partitionBySink,
                @Nullable ColumnTypes keyTypes,
                boolean isOrdered,
                boolean baseSupportsRandomAccess
        ) {
            sqlExecutionContext.configureAnalyticContext(partitionByRecord, partitionBySink, keyTypes, isOrdered, baseSupportsRandomAccess);
        }

        @Override
        public void initNow() {
            sqlExecutionContext.initNow();
        }

        @Override
        public long getNow() {
            return sqlExecutionContext.getNow();
        }

        @Override
        public int getJitMode() {
            return sqlExecutionContext.getJitMode();
        }

        @Override
        public void setJitMode(int jitMode) {
            sqlExecutionContext.setJitMode(jitMode);
        }

        @Override
        public void setCloneSymbolTables(boolean cloneSymbolTables) {
            sqlExecutionContext.setCloneSymbolTables(cloneSymbolTables);
        }

        @Override
        public boolean getCloneSymbolTables() {
            return sqlExecutionContext.getCloneSymbolTables();
        }
    }

    @Test
    public void testDeferredSymbolInFilter2() throws Exception {
        withPool((engine, compiler, sqlExecutionContext) -> testDeferredSymbolInFilter0(compiler, sqlExecutionContext));
    }

    @Test
    public void testDeferredSymbolInFilter2TwoPools() throws Exception {
        withDoublePool((engine, compiler, sqlExecutionContext) -> testDeferredSymbolInFilter0(compiler, sqlExecutionContext));
    }

    private void testDeferredSymbolInFilter0(SqlCompiler compiler, SqlExecutionContext sqlExecutionContext) throws SqlException {
        // JIT compiler doesn't support IN operator for symbols.
        sqlExecutionContext.setJitMode(SqlJitMode.JIT_MODE_DISABLED);
        compiler.compile("create table x as (select rnd_symbol('A','B') s, timestamp_sequence(20000000, 100000) t from long_sequence(500000)) timestamp(t) partition by hour", sqlExecutionContext);

        final String sql = "select * from x where s in ('C','D') limit 10";
        try (final RecordCursorFactory factory = compiler.compile(sql, sqlExecutionContext).getRecordCursorFactory()) {
            Assert.assertEquals(AsyncFilteredRecordCursorFactory.class, factory.getClass());

            assertCursor(
                    "s\tt\n",
                    factory,
                    true,
                    true,
                    false,
                    false,
                    sqlExecutionContext
            );

            compiler.compile("insert into x select rnd_symbol('C','D') s, timestamp_sequence(1000000000, 100000) from long_sequence(100)", sqlExecutionContext);

            // Verify that all symbol tables (original and views) are refreshed to include the new symbols.
            assertCursor(
                    "s\tt\n" +
                            "C\t1970-01-01T00:16:40.000000Z\n" +
                            "C\t1970-01-01T00:16:40.100000Z\n" +
                            "D\t1970-01-01T00:16:40.200000Z\n" +
                            "C\t1970-01-01T00:16:40.300000Z\n" +
                            "D\t1970-01-01T00:16:40.400000Z\n" +
                            "C\t1970-01-01T00:16:40.500000Z\n" +
                            "D\t1970-01-01T00:16:40.600000Z\n" +
                            "D\t1970-01-01T00:16:40.700000Z\n" +
                            "C\t1970-01-01T00:16:40.800000Z\n" +
                            "D\t1970-01-01T00:16:40.900000Z\n",
                    factory,
                    true,
                    true,
                    false,
                    false,
                    sqlExecutionContext
            );
        }
        resetTaskCapacities();
    }
}
