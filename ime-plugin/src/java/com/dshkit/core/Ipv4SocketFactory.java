package com.dshkit.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

/**
 * 强制 IPv4 的 SocketFactory，给 Paho 用。
 *
 * 为什么要它：`todo.ieew.cc` 有 AAAA 记录，但服务端的 MQTT 只监听 IPv4。
 * Android 进程解析域名时先拿 IPv6，Paho 直接连 `240e:...:1883` 会 ECONNREFUSED，
 * 表现为「连接失败」且 message 为空（MqttException code=32103）。
 *
 * 做法：无参 createSocket() 返回一个自定义 Socket，在 connect() 里把目标重新解析成
 * IPv4 再连；没有 IPv4 记录时才退化用原始地址（局域网/纯 IPv6 环境不受影响）。
 */
public final class Ipv4SocketFactory extends SocketFactory {

    @Override
    public Socket createSocket() {
        return new Ipv4Socket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port), 0);
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localHost, localPort));
        socket.connect(new InetSocketAddress(host, port), 0);
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port), 0);
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port), 0);
        return socket;
    }

    static InetAddress resolveIpv4(String host) throws UnknownHostException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress address : all) {
            if (address instanceof Inet4Address) return address;
        }
        return all.length > 0 ? all[0] : InetAddress.getByName(host);
    }

    private static final class Ipv4Socket extends Socket {
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (endpoint instanceof InetSocketAddress) {
                InetSocketAddress addr = (InetSocketAddress) endpoint;
                InetAddress target = resolveIpv4(addr.getHostString());
                super.connect(new InetSocketAddress(target, addr.getPort()), timeout);
                return;
            }
            super.connect(endpoint, timeout);
        }
    }
}
