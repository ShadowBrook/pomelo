package com.github.moxib.pomelo.config;

import io.github.shadowbrook.RedisClusterManager;
import io.github.shadowbrook.config.RedisConfig;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.EventBusOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Vert.x 集群工具。
 * 通过 {@code vertx-redis-clustermanager} 实现 Redis 集群。
 *
 * <h3>配置方式</h3>
 * 遵循项目统一配置规范，优先级从低到高：
 * <ol>
 *   <li>{@code conf/config.yaml} 中的 {@code redis.cm.*} 默认值</li>
 *   <li>{@code POMELO_REDIS_CM_*} 环境变量覆盖</li>
 * </ol>
 *
 * <pre>
 *   # config.yaml
 *   redis:
 *     cm:
 *       endpoint: "redis://127.0.0.1:6379"
 *       keyNamespace: "pomelo"
 *       username: ""
 *       password: ""
 * </pre>
 *
 * 使用方式：
 * <pre>
 *   java -Dvertx.cluster=true -jar pomelo-gateway.jar
 *   POMELO_REDIS_CM_ENDPOINT=redis://redis-prod:6379 java -Dvertx.cluster=true -jar ...
 * </pre>
 */
public final class ClusterHelper {

  private static final Logger LOG = LoggerFactory.getLogger(ClusterHelper.class);

  private static final String YAML_PATH = "conf/config.yaml";

  private ClusterHelper() {}

  /**
   * 创建 Vert.x 实例，自动检测集群模式。
   */
  public static Vertx createVertx() {
    if (!isClustered()) {
      LOG.info("Creating standalone (non-clustered) Vert.x instance");
      return Vertx.vertx();
    }

    RedisConfig redisConfig = loadRedisConfig();
    RedisClusterManager clusterManager = new RedisClusterManager(redisConfig);
    LOG.info("Creating clustered Vert.x instance with Redis CM (endpoints: {})",
        redisConfig.getEndpoints());
    if (redisConfig.getKeyNamespace() != null && !redisConfig.getKeyNamespace().isEmpty()) {
      LOG.info("Redis CM key namespace: {}", redisConfig.getKeyNamespace());
    }

    VertxOptions options = new VertxOptions()
        .setEventBusOptions(new EventBusOptions());

    try {
      Vertx vertx = Vertx.builder()
          .with(options)
          .withClusterManager(clusterManager)
          .buildClustered()
          .toCompletionStage()
          .toCompletableFuture()
          .get(60, TimeUnit.SECONDS);
      LOG.info("Clustered Vert.x started, isClustered={}", vertx.isClustered());
      return vertx;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for clustered Vert.x", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new RuntimeException("Failed to create clustered Vert.x: " + e.getMessage(), e);
    }
  }

  /**
   * 加载 Redis 集群配置。
   * 先从 config.yaml 读取，再应用 POMELO_ 环境变量覆盖。
   */
  private static RedisConfig loadRedisConfig() {
    JsonObject yamlConfig = loadYamlConfig();

    // 从 YAML 读取 redis.cm 节点（ClusterManager 专用），不存在则用空对象
    JsonObject redisConfig = getNestedOrDefault(yamlConfig, "redis", new JsonObject());
    JsonObject cmConfig = getNestedOrDefault(redisConfig, "cm", new JsonObject());

    // 应用 POMELO_REDIS_CM_* 环境变量覆盖
    cmConfig = applyEnvOverrides(cmConfig, "POMELO_REDIS_CM_");

    return buildRedisConfig(cmConfig);
  }

  /**
   * 从 classpath 加载 YAML 配置文件。
   */
  private static JsonObject loadYamlConfig() {
    try (InputStream in = ClusterHelper.class.getClassLoader().getResourceAsStream(YAML_PATH)) {
      if (in == null) {
        LOG.warn("config.yaml not found on classpath, using defaults");
        return new JsonObject();
      }
      Yaml yaml = new Yaml();
      Map<String, Object> map = yaml.load(in);
      if (map == null) {
        return new JsonObject();
      }
      return new JsonObject(map);
    } catch (Exception e) {
      LOG.warn("Failed to load config.yaml: {}, using defaults", e.getMessage());
      return new JsonObject();
    }
  }

  /**
   * 按点分隔路径获取嵌套 JsonObject，不存在时返回默认值。
   */
  private static JsonObject getNestedOrDefault(JsonObject root, String path, JsonObject defaultValue) {
    String[] parts = path.split("\\.");
    JsonObject current = root;
    for (int i = 0; i < parts.length; i++) {
      if (current == null) {
        return defaultValue;
      }
      if (i == parts.length - 1) {
        JsonObject result = current.getJsonObject(parts[i]);
        return result != null ? result : defaultValue;
      }
      current = current.getJsonObject(parts[i]);
    }
    return defaultValue;
  }

  /**
   * 将环境变量中指定前缀的值覆盖到配置对象中。
   * POMELO_REDIS_CM_ADDRESS → address
   * POMELO_REDIS_CM_KEY_NAMESPACE → keyNamespace
   */
  private static JsonObject applyEnvOverrides(JsonObject base, String prefix) {
    JsonObject result = base.copy();
    System.getenv().forEach((key, value) -> {
      if (key.startsWith(prefix)) {
        String fieldName = key.substring(prefix.length())
            .toLowerCase();
        // 将下划线转为驼峰命名，匹配 config.yaml 中的字段名
        // POMELO_REDIS_CLUSTER_KEY_NAMESPACE → keyNamespace
        result.put(toCamelCase(fieldName), value);
        LOG.debug("Env override: {} -> {}", key, toCamelCase(fieldName));
      }
    });
    return result;
  }

  /**
   * 下划线分隔转驼峰：key_namespace → keyNamespace
   */
  private static String toCamelCase(String snakeCase) {
    StringBuilder sb = new StringBuilder();
    boolean upperNext = false;
    for (char c : snakeCase.toCharArray()) {
      if (c == '_') {
        upperNext = true;
      } else if (upperNext) {
        sb.append(Character.toUpperCase(c));
        upperNext = false;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * 从配置对象构建 RedisConfig。
   */
  private static RedisConfig buildRedisConfig(JsonObject cm) {
    RedisConfig config = new RedisConfig();

    String endpoint = cm.getString("endpoint");
    if (endpoint != null && !endpoint.isBlank()) {
      config.addEndpoint(endpoint);
    }

    String keyNamespace = cm.getString("keyNamespace");
    if (keyNamespace != null && !keyNamespace.isBlank()) {
      config.setKeyNamespace(keyNamespace);
    }

    String username = cm.getString("username");
    if (username != null && !username.isBlank()) {
      config.setUsername(username);
    }

    String password = cm.getString("password");
    if (password != null && !password.isBlank()) {
      config.setPassword(password);
    }

    return config;
  }

  /**
   * 是否启用集群模式。
   */
  public static boolean isClustered() {
    return Boolean.getBoolean("vertx.cluster") || "true".equals(System.getenv("VERTX_CLUSTER"));
  }

  /**
   * 非集群模式下告警：session 路由 / 跨实例 push 依赖集群 EventBus，
   * 非集群时 SessionRouteTable 会静默 no-op，多实例部署必须加 -Dvertx.cluster=true。
   */
  public static void warnIfNotClustered(Vertx vertx, String role) {
    if (vertx != null && !vertx.isClustered()) {
      LOG.warn("{} 以非集群模式启动：session 路由与跨实例 push 将退化（单进程广播 / 跨进程不可达），" +
        "多实例部署请加 -Dvertx.cluster=true", role);
    }
  }
}
