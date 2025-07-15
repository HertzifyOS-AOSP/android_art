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
import java.util.concurrent.atomic.AtomicInteger;

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
        testSleepingThreadPinning();
    }

    private static final Thread.UncaughtExceptionHandler HANDLER = (t, e) -> {
        System.err.println("thread: " + t.getName());
        e.printStackTrace(System.err);
        System.exit(1);
    };

    /**
     * Start {@code numOfThreads} virtual threads sleeping for the given duration and wait until
     * all threads join or time out.
     * @param numOfThreads number of concurrent virtual threads sleeping
     */
    private static void testNSleepingThreads(int numOfThreads) throws InterruptedException {
        long sleepDurationMs = 10;
        List<VirtualThread> threads = new ArrayList<>(numOfThreads);
        for (int i = 0; i < numOfThreads; i++) {
            VirtualThread vt = (VirtualThread) Thread.ofVirtual()
                    .uncaughtExceptionHandler(HANDLER)
                    .unstarted(() -> {
                        try {
                            Thread.sleep(sleepDurationMs);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    });
            vt.setJvmtiEventListener(new UnmountListener(vt));
            threads.add(vt);
            vt.start();
        }

        for (VirtualThread vt : threads) {
            vt.join();
        }

        for (VirtualThread vt : threads) {
            UnmountListener listener = (UnmountListener) vt.getJvmtiEventListener();
            int state = listener.state.get();
            if (listener.state.get() != UnmountListener.STATE_PARKED_AND_UNMOUNTED) {
                throw new AssertionError("Thread " + vt.threadId() + " wasn't parked. "
                        + "The state code was " + state);
            }
        }
    }

    private static void testSleepingThreadPinning() throws InterruptedException {
        VirtualThread vt = (VirtualThread) Thread.ofVirtual()
                .uncaughtExceptionHandler(HANDLER)
                .unstarted(() -> {
                    try {
                        Thread t = Thread.currentThread();
                        synchronized (t) {
                            Thread.sleep(10);
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
        UnmountListener listener = new UnmountListener(vt);
        vt.setJvmtiEventListener(listener);
        vt.start();
        vt.join();

        int state = listener.state.get();
        // When the virtual thread terminates, it's unmounted from the carrier thread.
        if (listener.state.get() != UnmountListener.STATE_UNMOUNTED) {
            throw new AssertionError("Thread " + vt.threadId() + " wasn't pinned. "
                    + "The state code was " + state);
        }
    }

    private static class UnmountListener implements VirtualThread.JvmtiEventsListener {

        private static final int STATE_NOT_UNMOUNTED = 0;
        private static final int STATE_UNMOUNTED = 1;
        private static final int STATE_PARKED_AND_UNMOUNTED = 2;
        private final VirtualThread vt;
        // Indicate if the virtual thread is ever parked and unmounted.
        private final AtomicInteger state = new AtomicInteger(STATE_NOT_UNMOUNTED);

        public UnmountListener(VirtualThread vt) {
            this.vt = vt;
        }

        @Override
        public void onJvmtiUnmount(boolean hide) {
            if (!hide && state.get() != STATE_PARKED_AND_UNMOUNTED) {
                if (vt.getVirtualThreadContext().isUnmounted()) {
                    state.set(STATE_PARKED_AND_UNMOUNTED);
                } else {
                    state.set(STATE_UNMOUNTED);
                }
            }
        }
    }
}
