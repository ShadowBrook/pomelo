package com.github.moxib.pomelo.config;

import io.vertx.core.net.PemKeyCertOptions;

/**
 * 服务端 TLS 配置（网关 TCP/WS 与 HTTP API 共用同一张证书）。
 * <p>
 * 开关与证书路径来自配置（支持 POMELO_* 环境变量覆盖）：
 * <ul>
 *   <li>{@code tls.enabled} — 是否启用 TLS（默认 false，便于本地无证书开发）</li>
 *   <li>{@code tls.certPath} / {@code tls.keyPath} — PEM 证书与私钥路径</li>
 * </ul>
 * 未启用时 {@link #pemKeyCert()} 返回 null，调用方据此走明文（保持开发便利）。
 */
public final class TlsConfig {

  private TlsConfig() {}

  public static boolean enabled() {
    return ConfigHolder.getBoolean("tls.enabled", false);
  }

  public static String certPath() {
    return ConfigHolder.getString("tls.certPath", "conf/tls/server.crt");
  }

  public static String keyPath() {
    return ConfigHolder.getString("tls.keyPath", "conf/tls/server.key");
  }

  /** 启用 TLS 时返回 PEM 证书配置，否则 null */
  public static PemKeyCertOptions pemKeyCert() {
    if (!enabled()) {
      return null;
    }
    return new PemKeyCertOptions().setCertPath(certPath()).setKeyPath(keyPath());
  }
}
