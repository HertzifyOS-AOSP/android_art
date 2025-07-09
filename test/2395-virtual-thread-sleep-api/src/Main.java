/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verify that {@link Thread#sleep(long)} API parks virtual threads.
 */
public class Main {

    public static void main(String[] args) throws InterruptedException, ClassNotFoundException {
        if (!com.android.art.flags.Flags.virtualThreadImplV1()) {
            return;
        }
        // Exit if the thread throws any exception.
        Thread.setDefaultUncaughtExceptionHandler(HANDLER);

        testNSleepingThreads(2);
        testNSleepingThreads(10);
        testNSleepingThreads(100);
        testNSleepingThreads(300);
    }

    private static final Thread.UncaughtExceptionHandler HANDLER = (t, e) -> {
        System.err.println("thread: " + t.getName());
        e.printStackTrace(System.err);
        System.exit(1);
    };

    private static final int SLEEP_DURATION_MULTIPLIER = 10;

    /**
     * Start {@code numOfThreads} virtual threads sleeping for the given duration and wait until
     * all threads join or time out.
     * @param numOfThreads number of concurrent virtual threads sleeping
     */
    private static void testNSleepingThreads(int numOfThreads) throws InterruptedException {
        long sleepDurationMs = Math.max(numOfThreads * SLEEP_DURATION_MULTIPLIER, 1500);
        long timeoutThresholdNs = TimeUnit.MILLISECONDS.toNanos(
                sleepDurationMs * 3);
        List<Thread> threads = new ArrayList<>(numOfThreads);
        CountDownLatch latch = new CountDownLatch(numOfThreads);
        for (int i = 0; i < numOfThreads; i++) {
            Thread vt = Thread.ofVirtual().uncaughtExceptionHandler(HANDLER).start(() -> {
                latch.countDown();
                try {
                    Thread.sleep(sleepDurationMs);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            threads.add(vt);
        }

        List<Thread> threadsToBeUnmounted = new ArrayList<>(threads);
        long startTime = System.nanoTime();
        while (!threadsToBeUnmounted.isEmpty()) {
            if (latch.getCount() > 0) {
                // Reset the timer if some threads are not started.
                startTime = System.nanoTime();
            } else if (System.nanoTime() - startTime > timeoutThresholdNs) {
                Thread vt = threadsToBeUnmounted.getFirst();
                throw new AssertionError("Thread " + vt.threadId() + " wasn't "
                        + "unmounted. Consider increasing SLEEP_DURATION_MULTIPLIER for slow test "
                        + "configurations.");
            }

            // We can't assert that a virtual thread runs on different carrier thread before parking
            // and after un-parking because it is backed by carrier threads from a thread pool.
            // Instead, we verify that it's unmounted in a busy loop ,
            threadsToBeUnmounted.removeIf(vt -> vt.getVirtualThreadContext().isUnmounted());


        }

        for (Thread vt : threads) {
            vt.join();
        }
    }
}
