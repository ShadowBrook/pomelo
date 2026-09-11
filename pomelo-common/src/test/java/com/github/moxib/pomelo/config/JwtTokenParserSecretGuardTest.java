package com.github.moxib.pomelo.config;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JWT 密钥护栏：HS256 是对称算法，密钥即签发权。
 * 用仓库内置（公开）密钥启动等于允许任何拿到仓库的人冒充任意用户，必须拒绝。
 */
@DisplayName("JWT 密钥启动护栏")
class JwtTokenParserSecretGuardTest {

  @Test
  @DisplayName("未配置密钥（回退到内置开发密钥）→ 拒绝启动")
  void rejectsBuiltInDevSecret() {
    withProperty("jwt.secret", null, () ->
      assertThrows(IllegalStateException.class,
        () -> new JwtTokenParser(null),
        "拿到仓库即可签发任意 token 的密钥不得静默放行"));
  }

  @Test
  @DisplayName("显式设置的内置密钥同样被拒")
  void rejectsExplicitlyConfiguredBuiltInSecret() {
    withProperty("jwt.secret", JwtTokenParser.DEV_DEFAULT_SECRET, () ->
      assertThrows(IllegalStateException.class, () -> new JwtTokenParser(null)));
  }

  @Test
  @DisplayName("空白密钥被拒")
  void rejectsBlankSecret() {
    withProperty("jwt.secret", "   ", () ->
      assertThrows(IllegalStateException.class, () -> new JwtTokenParser(null)));
  }

  @Test
  @DisplayName("独立密钥可正常构造")
  void acceptsDistinctSecret() {
    withProperty("jwt.secret", "a-secret-that-is-not-in-the-repo", () -> {
      Vertx vertx = Vertx.vertx();
      try {
        assertNotNull(new JwtTokenParser(vertx));
      } finally {
        vertx.close();
      }
    });
  }

  @Test
  @DisplayName("本地开发可显式放行内置密钥")
  void allowsBuiltInSecretWhenExplicitlyOptedIn() {
    withProperty("jwt.allowDefaultSecret", "true", () -> {
      Vertx vertx = Vertx.vertx();
      try {
        assertNotNull(new JwtTokenParser(vertx));
      } finally {
        vertx.close();
      }
    });
  }

  /** 临时设置系统属性（配置未加载时 ConfigHolder 走系统属性回退），执行后恢复 */
  private static void withProperty(String key, String value, Runnable action) {
    String previous = System.getProperty(key);
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
    try {
      action.run();
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }
}
