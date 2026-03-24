package com.github.moxib.pomelo.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonCodec 测试")
public class JsonCodecTest {

    static class TestMessage {
        private String id;
        private String content;
        private int count;
        private boolean active;
        private Map<String, String> metadata;

        public TestMessage() {}

        public TestMessage(String id, String content, int count, boolean active) {
            this.id = id;
            this.content = content;
            this.count = count;
            this.active = active;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            TestMessage that = (TestMessage) obj;
            return count == that.count &&
                   active == that.active &&
                   (id == null ? that.id == null : id.equals(that.id)) &&
                   (content == null ? that.content == null : content.equals(that.content));
        }
    }

    @Test
    @DisplayName("测试简单对象编解码")
    void testSimpleObjectEncodeDecode() {
        TestMessage original = new TestMessage("msg-001", "Hello World", 42, true);

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        TestMessage decoded = codec.decode(encoded);

        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getContent(), decoded.getContent());
        assertEquals(original.getCount(), decoded.getCount());
        assertEquals(original.isActive(), decoded.isActive());
    }

    @Test
    @DisplayName("测试包含 Map 字段的对象编解码")
    void testObjectWithMapFieldEncodeDecode() {
        TestMessage original = new TestMessage("msg-002", "Test with metadata", 100, false);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", "value2");
        metadata.put("userId", "user-12345");
        original.setMetadata(metadata);

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getContent(), decoded.getContent());
        assertEquals(original.getMetadata().size(), decoded.getMetadata().size());
        assertEquals(original.getMetadata(), decoded.getMetadata());
    }

    @Test
    @DisplayName("测试包含中文内容的对象编解码")
    void testObjectWithChineseContentEncodeDecode() {
        TestMessage original = new TestMessage("msg-003", "你好，世界！", 0, true);

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getContent(), decoded.getContent());
    }

    @Test
    @DisplayName("测试空字符串字段编解码")
    void testObjectWithEmptyStringEncodeDecode() {
        TestMessage original = new TestMessage("", "", 0, false);

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertEquals("", decoded.getId());
        assertEquals("", decoded.getContent());
        assertEquals(0, decoded.getCount());
        assertFalse(decoded.isActive());
    }

    @Test
    @DisplayName("测试 null 字段编解码")
    void testObjectWithNullFieldsEncodeDecode() {
        TestMessage original = new TestMessage();
        original.setId(null);
        original.setContent(null);
        original.setCount(0);
        original.setActive(false);

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertNull(decoded.getId());
        assertNull(decoded.getContent());
        assertEquals(0, decoded.getCount());
        assertFalse(decoded.isActive());
    }

    @Test
    @DisplayName("测试包含特殊字符的内容编解码")
    void testObjectWithSpecialCharactersEncodeDecode() {
        TestMessage original = new TestMessage(
            "msg-special",
            "Special chars: \n\t\r\"\\{}[]:<>?/@!@#$%^&*()",
            999,
            true
        );

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getContent(), decoded.getContent());
    }

    @Test
    @DisplayName("测试包含 Unicode 字符的内容编解码")
    void testObjectWithUnicodeContentEncodeDecode() {
        TestMessage original = new TestMessage(
            "msg-unicode",
            "Unicode: \u00E9\u00E8\u00EA \u4E2D\u6587 \u65E5\u672C\u8A9E \uD55C\uAD6D\uC5B4",
            1,
            true
        );

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertEquals(original.getId(), decoded.getId());
        assertEquals(original.getContent(), decoded.getContent());
    }

    @Test
    @DisplayName("测试大数字段编解码")
    void testObjectWithLargeNumberEncodeDecode() {
        TestMessage original = new TestMessage("msg-large", "test", Integer.MAX_VALUE, true);

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertEquals(original.getCount(), decoded.getCount());
        assertEquals(Integer.MAX_VALUE, decoded.getCount());
    }

    @Test
    @DisplayName("测试负数字段编解码")
    void testObjectWithNegativeNumberEncodeDecode() {
        TestMessage original = new TestMessage("msg-negative", "test", -1000, false);

        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        byte[] encoded = codec.encode(original);
        TestMessage decoded = codec.decode(encoded);

        assertEquals(original.getCount(), decoded.getCount());
        assertEquals(-1000, decoded.getCount());
    }

    @Test
    @DisplayName("测试 getCodecId 返回正确的值")
    void testGetCodecId() {
        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);
        assertEquals((byte) 1, codec.getCodecId());
    }

    @Test
    @DisplayName("测试 getMessageType 返回正确的类型")
    void testGetMessageType() {
        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);
        assertEquals(TestMessage.class, codec.getMessageType());
    }

    @Test
    @DisplayName("测试损坏的 JSON 数据抛出异常")
    void testCorruptedDataThrowsException() {
        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);
        byte[] corruptedData = "invalid json {".getBytes();

        assertThrows(RuntimeException.class, () -> {
            codec.decode(corruptedData);
        });
    }

    @Test
    @DisplayName("测试空字节数组抛出异常")
    void testEmptyByteArrayThrowsException() {
        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);
        byte[] emptyData = new byte[0];

        assertThrows(RuntimeException.class, () -> {
            codec.decode(emptyData);
        });
    }

    @Test
    @DisplayName("测试字段类型不匹配抛出异常")
    void testFieldTypeMismatchThrowsException() {
        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);
        // JSON 中 count 字段是字符串而不是数字
        String invalidJson = "{\"id\":\"test\",\"content\":\"test\",\"count\":\"not-a-number\",\"active\":true}";
        byte[] data = invalidJson.getBytes();

        assertThrows(RuntimeException.class, () -> {
            codec.decode(data);
        });
    }

    @Test
    @DisplayName("测试多次编解码一致性")
    void testMultipleEncodeDecodeConsistency() {
        TestMessage original = new TestMessage("msg-consistency", "Consistency test", 12345, true);
        JsonCodec<TestMessage> codec = new JsonCodec<>(TestMessage.class);

        TestMessage previous = null;
        for (int i = 0; i < 10; i++) {
            byte[] encoded = codec.encode(original);
            TestMessage decoded = codec.decode(encoded);

            assertEquals(original.getId(), decoded.getId());
            assertEquals(original.getContent(), decoded.getContent());
            assertEquals(original.getCount(), decoded.getCount());
            assertEquals(original.isActive(), decoded.isActive());

            if (previous != null) {
                assertArrayEquals(codec.encode(previous), encoded);
            }
            previous = decoded;
            original = decoded;
        }
    }

    @Test
    @DisplayName("测试嵌套对象编解码")
    void testNestedObjectEncodeDecode() {
        OuterObject original = new OuterObject("outer-001", new InnerObject("inner-001", 42));

        JsonCodec<OuterObject> codec = new JsonCodec<>(OuterObject.class);

        byte[] encoded = codec.encode(original);
        OuterObject decoded = codec.decode(encoded);

        assertEquals(original.id, decoded.id);
        assertNotNull(decoded.inner);
        assertEquals(original.inner.name, decoded.inner.name);
        assertEquals(original.inner.value, decoded.inner.value);
    }

    // 静态内部类用于测试
    public static class InnerObject {
        public String name;
        public int value;

        public InnerObject() {}

        public InnerObject(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    public static class OuterObject {
        public String id;
        public InnerObject inner;

        public OuterObject() {}

        public OuterObject(String id, InnerObject inner) {
            this.id = id;
            this.inner = inner;
        }
    }
}
