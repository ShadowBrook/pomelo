package com.github.moxib.pomelo.logic.service;

import com.github.moxib.pomelo.config.ConfigHolder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.StartTLSOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 邮件发送（vertx-mail-client，全程异步非阻塞，不占业务线程）。
 * SMTP 参数取自 conf/config.yaml 的 mail.*，可经 POMELO_MAIL_* 环境变量覆盖
 * （服务器用 conf/mail.env 注入，见部署文档）。
 * host/from 任一为空即视为未配置：configured() 返回 false，调用方应明确报错而不是静默失败。
 */
public class MailService {

  private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

  private final Vertx vertx;
  private volatile MailClient client;

  public MailService(Vertx vertx) {
    this.vertx = vertx;
  }

  /** 是否已配置 SMTP（未配置时发送前就该拒绝，避免"请求成功但邮件没发出去"） */
  public boolean configured() {
    return !blank(cfg("mail.host", "")) && !blank(cfg("mail.from", ""));
  }

  public String from() {
    return cfg("mail.from", "");
  }

  public int codeTtlSeconds() {
    return ConfigHolder.getInt("mail.codeTtlSeconds", 600);
  }

  public int sendCooldownSeconds() {
    return ConfigHolder.getInt("mail.sendCooldownSeconds", 60);
  }

  /** 发送纯文本邮件；失败返回 failedFuture（调用方决定是否对外暴露细节） */
  public Future<Void> send(String to, String subject, String text) {
    if (!configured()) {
      return Future.failedFuture(new IllegalStateException("邮件服务未配置"));
    }
    MailMessage message = new MailMessage()
      .setFrom(from())
      .setTo(to)
      .setSubject(subject)
      .setText(text);
    return client().sendMail(message)
      .map((Void) null)
      .onSuccess(r -> LOG.info("邮件已发送: to={} subject={}", mask(to), subject))
      .onFailure(e -> LOG.warn("邮件发送失败: to={} err={}", mask(to), e.getMessage()));
  }

  /** 懒建单例客户端（参数只在首次构造时读取，改配置需重启进程） */
  private MailClient client() {
    MailClient c = client;
    if (c == null) {
      synchronized (this) {
        if (client == null) {
          MailConfig config = new MailConfig()
            .setHostname(cfg("mail.host", ""))
            .setPort(ConfigHolder.getInt("mail.port", 465))
            .setUsername(cfg("mail.username", ""))
            .setPassword(cfg("mail.password", ""))
            .setSsl(ConfigHolder.getBoolean("mail.ssl", true))
            .setStarttls(ConfigHolder.getBoolean("mail.starttls", false)
              ? StartTLSOptions.REQUIRED : StartTLSOptions.DISABLED)
            .setKeepAlive(true);
          client = MailClient.createShared(vertx, config, "pomelo-mail");
        }
        c = client;
      }
    }
    return c;
  }

  private static String cfg(String key, String def) {
    String v = ConfigHolder.getString(key, def);
    return v == null ? def : v.trim();
  }

  private static boolean blank(String s) {
    return s == null || s.isEmpty();
  }

  /** 日志里不落完整收件地址 */
  private static String mask(String addr) {
    if (addr == null || addr.isEmpty()) {
      return "";
    }
    int at = addr.indexOf('@');
    if (at <= 1) {
      return "*" + (at >= 0 ? addr.substring(at) : "");
    }
    return addr.charAt(0) + "***" + addr.substring(at);
  }
}
