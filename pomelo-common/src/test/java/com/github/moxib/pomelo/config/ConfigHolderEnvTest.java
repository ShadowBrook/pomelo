package com.github.moxib.pomelo.config;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * POMELO_* 环境变量覆盖的路径匹配测试。
 * 驼峰配置键（media.publicEndpoint）必须能被下划线风格环境变量命中——
 * 历史实现按下划线转点直映射成 media.public.endpoint 而静默失效。
 */
@DisplayName("ConfigHolder 环境变量覆盖测试")
class ConfigHolderEnvTest {

  private static JsonObject base() {
    return new JsonObject()
      .put("database", new JsonObject().put("host", "localhost"))
      .put("media", new JsonObject().put("publicEndpoint", "http://localhost:9002"))
      .put("livekit", new JsonObject().put("publicUrl", "wss://default"));
  }

  @Test
  @DisplayName("下划线环境变量命中驼峰配置键")
  void camelCaseKeyMatchedByUnderscoreEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("MEDIA_PUBLIC_ENDPOINT", "https://oss.pomelo.host");
    env.put("DATABASE_HOST", "db.example.com");
    env.put("LIVEKIT_PUBLIC_URL", "wss://override/lk");

    JsonObject out = ConfigHolder.applyEnv(base(), env);

    assertEquals("https://oss.pomelo.host", out.getJsonObject("media").getString("publicEndpoint"));
    assertEquals("db.example.com", out.getJsonObject("database").getString("host"));
    assertEquals("wss://override/lk", out.getJsonObject("livekit").getString("publicUrl"));
  }

  @Test
  @DisplayName("无环境变量时保持原配置")
  void noEnvKeepsBase() {
    JsonObject out = ConfigHolder.applyEnv(base(), Map.of());
    assertEquals("http://localhost:9002", out.getJsonObject("media").getString("publicEndpoint"));
  }
}
