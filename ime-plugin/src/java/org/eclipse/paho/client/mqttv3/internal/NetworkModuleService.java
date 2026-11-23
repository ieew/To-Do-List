package org.eclipse.paho.client.mqttv3.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * 顶替 Paho 自带的 NetworkModuleService（构建时会把原类从 jar 里剔掉）。
 *
 * 为什么：Paho 用 `ServiceLoader.load(NetworkModuleFactory.class)` 找 tcp/ssl 实现，
 * 而 ServiceLoader 依赖 jar 里的 `META-INF/services/...` 资源文件；d8 打 dex **不带资源**，
 * 于是真机上抛 `no NetworkModule installed for scheme "tcp"`，MQTT 连不上。
 *
 * 这里直接 new 出 TCP/SSL 的 factory，跳过 ServiceLoader。除这两个 scheme 之外
 * （ws/wss 我们不用，设计里也否掉了 WebSocket）一律按不支持处理。
 *
 * applyRFC3986AuthorityPatch / setURIField 与上游实现一致（Apache-2.0, Eclipse Paho）。
 */
public class NetworkModuleService {

    private static final Pattern AUTHORITY_PATTERN =
            Pattern.compile("((.+)@)?([^:]*)(:(\\d+))?");

    private NetworkModuleService() {
    }

    public static void validateURI(String brokerUri) throws IllegalArgumentException {
        try {
            URI uri = new URI(brokerUri);
            applyRFC3986AuthorityPatch(uri);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty()) {
                throw new IllegalArgumentException("missing scheme in broker URI: " + brokerUri);
            }
            scheme = scheme.toLowerCase();
            if (!"tcp".equals(scheme) && !"ssl".equals(scheme)) {
                throw new IllegalArgumentException("no NetworkModule installed for scheme \""
                        + scheme + "\" of URI \"" + brokerUri + "\"");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(brokerUri, e);
        }
    }

    public static NetworkModule createInstance(String address, MqttConnectOptions options,
                                               String clientId)
            throws MqttException, IllegalArgumentException {
        try {
            URI uri = new URI(address);
            applyRFC3986AuthorityPatch(uri);
            String scheme = uri.getScheme();
            if (scheme == null) {
                throw new IllegalArgumentException(address);
            }
            scheme = scheme.toLowerCase();
            if ("tcp".equals(scheme)) {
                return new TCPNetworkModuleFactory().createNetworkModule(uri, options, clientId);
            }
            if ("ssl".equals(scheme)) {
                return new SSLNetworkModuleFactory().createNetworkModule(uri, options, clientId);
            }
            throw new IllegalArgumentException(address);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(address, e);
        }
    }

    public static void applyRFC3986AuthorityPatch(URI uri) {
        if (uri == null || uri.getHost() != null || uri.getAuthority() == null
                || uri.getAuthority().isEmpty()) {
            return;
        }
        Matcher matcher = AUTHORITY_PATTERN.matcher(uri.getAuthority());
        if (matcher.find()) {
            setURIField(uri, "userInfo", matcher.group(2));
            setURIField(uri, "host", matcher.group(3));
            String portString = matcher.group(5);
            setURIField(uri, "port", Integer.valueOf(
                    portString != null ? Integer.parseInt(portString) : -1));
        }
    }

    private static void setURIField(URI uri, String name, Object value) {
        try {
            java.lang.reflect.Field field = URI.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(uri, value);
        } catch (Throwable ignored) {
            // 打不上补丁就按 Java 自己的解析结果走
        }
    }
}
