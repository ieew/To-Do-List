package com.dshkit.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import javax.net.SocketFactory;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.internal.NetworkModule;

/**
 * 双栈（Happy Eyeballs 简化版）TCP 网络模块，给 Paho 用。
 *
 * 背景：`todo.ieew.cc` 同时有 A/AAAA 记录。只连一种地址会遇到两种坑：
 * 只连 IPv6 → 服务端 broker 没监听 IPv6 时 ECONNREFUSED；
 * 只连 IPv4 → 服务端开了 IPv6 后连不上 IPv6-only 客户端。
 *
 * 这里按 IPv6 优先、逐地址尝试（每个地址最多 3 秒），任何一个能连上就用它；
 * 全部失败才抛最后一个异常。真正双栈的环境里，两边谁先可用就走谁。
 */
public final class DualStackNetworkModule implements NetworkModule {

    private static final int PER_ADDRESS_TIMEOUT_MS = 3000;

    private final MqttConnectOptions options;
    private final String host;
    private final int port;
    private final String serverURI;
    private Socket socket;
    private InputStream input;
    private OutputStream output;

    public DualStackNetworkModule(MqttConnectOptions options, String host, int port, String serverURI) {
        this.options = options;
        this.host = host;
        this.port = port;
        this.serverURI = serverURI;
    }

    @Override
    public void start() throws IOException {
        IOException lastError = null;
        int timeout = options == null ? 0 : options.getConnectionTimeout() * 1000;
        int perTry = timeout > 0 ? Math.min(timeout, PER_ADDRESS_TIMEOUT_MS) : PER_ADDRESS_TIMEOUT_MS;
        for (InetAddress target : orderedAddresses(host)) {
            Socket candidate = createSocket();
            try {
                candidate.connect(new InetSocketAddress(target, port), perTry);
                socket = candidate;
                break;
            } catch (IOException e) {
                lastError = e;
                closeQuietly(candidate);
            }
        }
        if (socket == null) {
            throw lastError != null ? lastError
                    : new IOException("connect failed: " + host + ":" + port);
        }
        // Paho 用 1s 读超时做轮询（与 TCPNetworkModule 一致）。
        socket.setSoTimeout(1000);
        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return input;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return output;
    }

    @Override
    public void stop() throws IOException {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    @Override
    public String getServerURI() {
        return serverURI;
    }

    private Socket createSocket() throws IOException {
        SocketFactory factory = options == null ? null : options.getSocketFactory();
        return factory == null ? new Socket() : factory.createSocket();
    }

    /** IPv6 在前、IPv4 在后；解析失败时把 host 原样交给系统（域名解析链可能托管给系统）。 */
    static List<InetAddress> orderedAddresses(String host) throws IOException {
        List<InetAddress> ordered = new ArrayList<InetAddress>();
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new IOException("resolve failed: " + host, e);
        }
        for (InetAddress address : all) {
            if (address instanceof Inet6Address) ordered.add(address);
        }
        for (InetAddress address : all) {
            if (!(address instanceof Inet6Address)) ordered.add(address);
        }
        return ordered;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Throwable ignored) {
            // 关闭失败不影响下一次尝试
        }
    }

    @SuppressWarnings("unused")
    private static boolean isTimeout(IOException e) {
        return e instanceof SocketTimeoutException;
    }
}
