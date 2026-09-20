package io.github.alonenannan.chevereto;

import java.net.InetAddress;
import java.net.URI;

public final class CheveretoUtils {
    private CheveretoUtils() {
    }

    /**
     * 校验 API 地址：仅允许 HTTPS 公网域名，防止 SSRF 攻击。
     */
    public static String validateApiUrl(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("API 地址格式不合法: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new RuntimeException("API 地址必须使用 HTTPS，当前为: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RuntimeException("API 地址缺少主机名");
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                throw new RuntimeException("不允许使用内网地址作为 API 地址");
            }
        } catch (java.net.UnknownHostException e) {
            throw new RuntimeException("无法解析 API 地址的主机名: " + host);
        }
        return rawUrl;
    }
}
