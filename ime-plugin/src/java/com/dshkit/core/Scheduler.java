package com.dshkit.core;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * L2 调度：一条后台线程 + 定时任务。
 *
 * 网络、批量拉取、重连退避都在这里排队，绝不占用输入法主线程。
 */
public final class Scheduler {

    private final ScheduledExecutorService pool;
    private final Log log;

    public Scheduler(String name, Log log) {
        this.log = log;
        this.pool = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        });
    }

    public void run(Runnable task) {
        guard(task);
    }

    public void delay(Runnable task, long delayMs) {
        if (delayMs <= 0) {
            run(task);
            return;
        }
        try {
            pool.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            if (log != null) log.e("sched", t);
        }
    }

    /** 同 {@link #delay}，但返回句柄，便于取消（例如手动重连时作废旧的退避任务）。 */
    public ScheduledFuture<?> delayFuture(Runnable task, long delayMs) {
        if (delayMs <= 0) {
            run(task);
            return null;
        }
        try {
            return pool.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            if (log != null) log.e("sched", t);
            return null;
        }
    }

    public void every(Runnable task, long initialMs, final long periodMs) {
        try {
            pool.scheduleWithFixedDelay(guarded(task), initialMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            if (log != null) log.e("sched", t);
        }
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long periodMs) {
        try {
            return pool.scheduleWithFixedDelay(guarded(task), periodMs, periodMs,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            if (log != null) log.e("sched", t);
            return null;
        }
    }

    private Runnable guarded(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable t) {
                    if (log != null) log.e("sched", t);
                }
            }
        };
    }

    private void guard(Runnable task) {
        try {
            pool.execute(guarded(task));
        } catch (Throwable t) {
            if (log != null) log.e("sched", t);
        }
    }

    /** 给 EventBus 用的执行器：确保所有回调都在同一条后台线程上串行。 */
    public java.util.concurrent.Executor executor() {
        return new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable command) {
                run(command);
            }
        };
    }

    public void shutdown() {
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
    }
}
