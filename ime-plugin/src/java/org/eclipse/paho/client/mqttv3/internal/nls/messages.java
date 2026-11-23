package org.eclipse.paho.client.mqttv3.internal.nls;

import java.util.Collections;
import java.util.Enumeration;
import java.util.ResourceBundle;

/**
 * Paho 的日志文案包。
 *
 * 原因：Paho 1.2.5 的 `LoggerFactory.getLogger()` 会 `ResourceBundle.getBundle(
 * "org.eclipse.paho.client.mqttv3.internal.nls.messages")`，而 d8 打 dex **不带
 * .properties 资源**，手机上一连就抛
 * `MissingResourceException: Can't find bundle ... locale zh_CN`，MQTT 直接连不上。
 *
 * 这里用同名类顶替资源包（ResourceBundle 允许用类实现），所有 key 返回空串，
 * 只为让 getBundle 成功。文案本来就只进日志，不需要翻译。
 */
public class messages extends ResourceBundle {

    public messages() {
    }

    @Override
    protected Object handleGetObject(String key) {
        return "";
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.emptyEnumeration();
    }
}
