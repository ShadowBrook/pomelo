package com.github.moxib.pomelo.logic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对象 key 形态与归属解析。
 * <p>
 * 对象名必须是不可枚举的随机值：对象名是访问媒体唯一需要知道的东西，
 * 用 Snowflake 这类单调递增 ID 时，知道大致上传时间即可批量枚举他人对象。
 */
@DisplayName("媒体对象 key")
class ObjectKeysTest {

  @Test
  @DisplayName("生成的 key 形态合法且对象名随机")
  void generatedKeyIsWellFormedAndRandom() {
    Set<String> names = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      String key = ObjectKeys.newKey("image", "100", "jpg");
      assertTrue(ObjectKeys.isWellFormed(key), "生成的 key 应自身合法: " + key);
      assertEquals("100", ObjectKeys.ownerOf(key));
      names.add(key);
    }
    assertEquals(200, names.size(), "对象名不得重复（重复意味着可被枚举/碰撞）");
  }

  @Test
  @DisplayName("兼容存量 Snowflake 命名的 key")
  void acceptsLegacySnowflakeKey() {
    String legacy = "voice/100/20260910/8841234567890123456.m4a";
    assertTrue(ObjectKeys.isWellFormed(legacy));
    assertEquals("100", ObjectKeys.ownerOf(legacy));
  }

  @Test
  @DisplayName("拒绝非服务端形态的 key")
  void rejectsForeignShapes() {
    for (String bad : new String[]{
      null, "", "   ",
      "img/1.jpg",
      "image/100/x.jpg",
      "image/100/20260910/nonhex-not-a-token.jpg",
      "image/100/20260910/0123456789abcdef0123456789abcdef.jpg.exe",
      "image/100/20260910/0123456789abcdef0123456789abcdef",
      "/image/100/20260910/0123456789abcdef0123456789abcdef.jpg",
      "image/100/20260910/0123456789abcdef0123456789abcdef.jpg/../../x",
      "http://evil/x.jpg",
    }) {
      assertFalse(ObjectKeys.isWellFormed(bad), "不应视为服务端签发的 key: " + bad);
      assertNull(ObjectKeys.ownerOf(bad), "非法 key 解析不出归属: " + bad);
    }
  }
}
