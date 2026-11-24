package com.todo.android.core.dsh;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

/**
 * 强制 IPv4 的 SocketFactory，给 Paho 用。
 *
 * 服务端 `todo.ieew.cc` 有 AAAA 记录但 broker 只监听 IPv4；Android 进程解析域名时
 * 先拿 IPv6，Paho 连 `240e:...:1883` 会 ECONNREFUSED（MqttException 32103、message 为空）。
 * 这里在 connect() 里重新解析成 IPv4 再连；没有 IPv4 记录时退化用原始地址。
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
