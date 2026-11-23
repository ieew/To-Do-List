package com.dshkit.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * L2 事件总线：模块之间不直接互调，一律发事件。
 *
 * 投递在执行器线程（我们只有一个后台线程），Android 侧需要主线程的自己再 post。
 */
public final class EventBus {

    public static final class Event {
        public final String type;
        public final Map<String, Object> data;

        public Event(String type, Map<String, Object> data) {
            this.type = type;
            this.data = data == null ? new LinkedHashMap<String, Object>() : data;
        }

        public String str(String key, String def) {
            return Json.str(data, key, def);
        }

        public long lng(String key, long def) {
            return Json.lng(data, key, def);
        }
    }

    public interface Handler {
        void onEvent(Event e);
    }

    private final Map<String, List<Handler>> handlers = new LinkedHashMap<String, List<Handler>>();
    private final Executor executor;
    private final Log log;

    public EventBus(Executor executor, Log log) {
        this.executor = executor;
        this.log = log;
    }

    public synchronized void on(String type, Handler handler) {
        List<Handler> list = handlers.get(type);
        if (list == null) {
            list = new ArrayList<Handler>();
            handlers.put(type, list);
        }
        list.add(handler);
    }

    public synchronized void onAny(Handler handler) {
        on("*", handler);
    }

    public void post(final Event event) {
        if (executor == null) {
            dispatch(event);
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                dispatch(event);
            }
        });
    }

    public void postNow(Event event) {
        dispatch(event);
    }

    private synchronized List<Handler> handlersFor(String type) {
        List<Handler> out = new ArrayList<Handler>();
        List<Handler> exact = handlers.get(type);
        if (exact != null) out.addAll(exact);
        List<Handler> any = handlers.get("*");
        if (any != null) out.addAll(any);
        return out;
    }

    private void dispatch(Event event) {
        for (Handler h : handlersFor(event.type)) {
            try {
                h.onEvent(event);
            } catch (Throwable t) {
                if (log != null) log.e("bus/" + event.type, t);
            }
        }
    }
}
