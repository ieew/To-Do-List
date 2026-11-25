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
        this(name, log, true);
    }

    /**
     * §7.1：非主实例（secondary）不该有自己的调度线程。
     *
     * 每个 Lua 入口都会新建一个 DexClassLoader，Kit 类因此是新的、静态字段重置为 null，
     * 一个进程里可能同时存在多份 Kit。主实例用 {@code System.getProperty} 判定（进程级，
     * 跨 ClassLoader 共享），其余构造时传 {@code enabled=false}：不建线程池，
     * 所有异步动作退化成同步执行，事件照常回调——只是不再占一条常驻线程。
     */
    public Scheduler(String name, Log log, boolean enabled) {
        this.log = log;
        if (!enabled) {
            this.pool = null;
            return;
        }
        this.pool = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        });
    }

    public boolean isEnabled() {
        return pool != null;
    }

    public void run(Runnable task) {
        if (pool == null) {
            guarded(task).run();
            return;
        }
        guard(task);
    }

    public void delay(Runnable task, long delayMs) {
        if (pool == null) return;
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
        if (pool == null) return null;
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
        if (pool == null) return;
        try {
            pool.scheduleWithFixedDelay(guarded(task), initialMs, periodMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            if (log != null) log.e("sched", t);
        }
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long periodMs) {
        if (pool == null) return null;
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
        if (pool == null) return;
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
    }
}
