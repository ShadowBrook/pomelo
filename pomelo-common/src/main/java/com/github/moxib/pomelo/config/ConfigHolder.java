package com.github.moxib.pomelo.config;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局配置持有者（单例）。
 *
 * 配置来源优先级（由低到高）：
 * 1. classpath:conf/config.yaml（默认值）
 * 2. 环境变量 POMELO_* 前缀（如 POMELO_DATABASE_HOST=pg.example.com 覆盖 database.host）
 * 3. 系统属性 -D（作为 fallback，保留向后兼容）
 *
 * 必须在应用启动时调用 {@link #load(Vertx)}，之后各模块通过 {@code getString/getInt/getLong/getBoolean}
 * 读取配置。在 {@code load()} 完成前调用访问器会自动 fallback 到系统属性/默认值。
 */
public final class ConfigHolder {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigHolder.class);

  private static volatile JsonObject config;
  private static volatile boolean loaded;
  private static volatile Promise<Void> loadPromise;

  private ConfigHolder() {}

  /**
   * 加载配置。幂等——多次调用返回同一个 Future。
   */
  public static Future<Void> load(Vertx vertx) {
    if (loaded) {
      return Future.succeededFuture();
    }

    synchronized (ConfigHolder.class) {
      if (loaded) {
        return Future.succeededFuture();
      }
      if (loadPromise != null) {
        return loadPromise.future();
      }

      loadPromise = Promise.promise();

      ConfigStoreOptions yamlStore = new ConfigStoreOptions()
        .setType("file")
        .setFormat("yaml")
        .setConfig(new JsonObject().put("path", "conf/config.yaml"));

      ConfigRetrieverOptions options = new ConfigRetrieverOptions()
        .addStore(yamlStore);

      ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

      retriever.getConfig()
        .onSuccess(merged -> {
          JsonObject overridden = applyEnvOverrides(merged);
          config = overridden;
          loaded = true;
          LOG.info("Configuration loaded ({} top-level keys)", config.size());
          loadPromise.complete();
        })
        .onFailure(err -> {
          LOG.error("Failed to load configuration", err);
          loadPromise.fail(err);
        });
    }

    return loadPromise.future();
  }

  /**
   * 遍历环境变量，将 POMELO_ 前缀的变量映射到嵌套路径。
   * 例如 POMELO_DATABASE_HOST=pg.example.com → database.host = "pg.example.com"
   */
  private static JsonObject applyEnvOverrides(JsonObject base) {
    JsonObject result = base.copy();
    System.getenv().forEach((key, value) -> {
      if (key.startsWith("POMELO_")) {
        String path = key.substring("POMELO_".length())
          .toLowerCase()
          .replace('_', '.');
        setNested(result, path, value);
        LOG.debug("Env override: {} = {}", path, value);
      }
    });
    return result;
  }

  /**
   * 按点分隔路径设置嵌套 JsonObject 值，自动推断数字和布尔类型。
   */
  private static void setNested(JsonObject root, String path, String value) {
    String[] parts = path.split("\\.");
    JsonObject current = root;
    for (int i = 0; i < parts.length - 1; i++) {
      JsonObject next = current.getJsonObject(parts[i]);
      if (next == null) {
        next = new JsonObject();
        current.put(parts[i], next);
      }
      current = next;
    }
    String lastPart = parts[parts.length - 1];
    // 尝试推断为数字
    try {
      if (value.contains(".")) {
        current.put(lastPart, Double.parseDouble(value));
      } else {
        long longVal = Long.parseLong(value);
        if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
          current.put(lastPart, (int) longVal);
        } else {
          current.put(lastPart, longVal);
        }
      }
    } catch (NumberFormatException e) {
      // 尝试推断为布尔
      if ("true".equalsIgnoreCase(value)) {
        current.put(lastPart, true);
      } else if ("false".equalsIgnoreCase(value)) {
        current.put(lastPart, false);
      } else {
        current.put(lastPart, value);
      }
    }
  }

  // ---- 公开访问器 ----

  /**
   * 获取完整配置对象（仅用于测试或特殊场景）。
   * @throws IllegalStateException 如果配置尚未加载
   */
  public static JsonObject getConfig() {
    if (config == null) {
      throw new IllegalStateException("ConfigHolder not loaded. Call load() first.");
    }
    return config;
  }

  /**
   * 获取字符串配置值。配置未加载时 fallback 到 System.getProperty。
   */
  public static String getString(String path, String defaultValue) {
    if (config == null) {
      return System.getProperty(path, defaultValue);
    }
    String val = getNestedString(path);
    if (val != null) {
      return val;
    }
    return System.getProperty(path, defaultValue);
  }

  /**
   * 获取整数配置值。配置未加载时 fallback 到 Integer.getInteger。
   */
  public static int getInt(String path, int defaultValue) {
    if (config == null) {
      return Integer.getInteger(path, defaultValue);
    }
    Integer val = getNestedInteger(path);
    if (val != null) {
      return val;
    }
    return Integer.getInteger(path, defaultValue);
  }

  /**
   * 获取长整数配置值。配置未加载时 fallback 到 Long.getLong。
   */
  public static long getLong(String path, long defaultValue) {
    if (config == null) {
      return Long.getLong(path, defaultValue);
    }
    Long val = getNestedLong(path);
    if (val != null) {
      return val;
    }
    return Long.getLong(path, defaultValue);
  }

  /**
   * 获取布尔配置值。配置未加载时 fallback 到 Boolean.getBoolean。
   */
  public static boolean getBoolean(String path, boolean defaultValue) {
    if (config == null) {
      return Boolean.getBoolean(path);
    }
    Boolean val = getNestedBoolean(path);
    if (val != null) {
      return val;
    }
    String sysProp = System.getProperty(path);
    return sysProp != null ? Boolean.parseBoolean(sysProp) : defaultValue;
  }

  // ---- 嵌套路径遍历 ----

  private static String getNestedString(String path) {
    String[] parts = path.split("\\.");
    JsonObject current = config;
    for (int i = 0; i < parts.length - 1; i++) {
      current = current.getJsonObject(parts[i]);
      if (current == null) {
        return null;
      }
    }
    return current.getString(parts[parts.length - 1]);
  }

  private static Integer getNestedInteger(String path) {
    String[] parts = path.split("\\.");
    JsonObject current = config;
    for (int i = 0; i < parts.length - 1; i++) {
      current = current.getJsonObject(parts[i]);
      if (current == null) {
        return null;
      }
    }
    return current.getInteger(parts[parts.length - 1]);
  }

  private static Long getNestedLong(String path) {
    String[] parts = path.split("\\.");
    JsonObject current = config;
    for (int i = 0; i < parts.length - 1; i++) {
      current = current.getJsonObject(parts[i]);
      if (current == null) {
        return null;
      }
    }
    return current.getLong(parts[parts.length - 1]);
  }

  private static Boolean getNestedBoolean(String path) {
    String[] parts = path.split("\\.");
    JsonObject current = config;
    for (int i = 0; i < parts.length - 1; i++) {
      current = current.getJsonObject(parts[i]);
      if (current == null) {
        return null;
      }
    }
    return current.getBoolean(parts[parts.length - 1]);
  }

  // ---- 测试支持（package-private） ----

  static void setForTest(JsonObject testConfig) {
    config = testConfig;
    loaded = true;
  }

  static void resetForTest() {
    config = null;
    loaded = false;
    loadPromise = null;
  }
}
