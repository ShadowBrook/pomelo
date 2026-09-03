package com.github.moxib.pomelo.codec;

import com.github.moxib.pomelo.proto.chat.ChatProto;
import com.github.moxib.pomelo.proto.common.CommonProto;
import com.google.protobuf.ByteString;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 序列化尺寸对比（方向 B 的量化验证）。
 *
 * 对比同一批“真实消息量级”语料在三种编码下的字节数：
 *   1. JSON        —— 与 pomelo-web protocol.ts 现在的 wire body 完全同构（UTF-8、无空格）
 *   2. Protobuf    —— 用现有 .proto（chat.C2CReq / chat.C2CNotify）编码同一逻辑消息
 *   3. JSON+deflate—— 两条路径：
 *        per-message ：每条消息独立 deflate（等价于“每次新建压缩流”，压缩率下限）
 *        stream       ：整条会话流共享一个 Deflater 窗口（permessage-deflate 常开的近似，压缩率上限）
 *
 * 说明：proto 里 id 是 int64，web 端是字符串（snowflake > 2^53 精度），这里用等值 long/string 编码，
 * 字节差异只来自格式本身；varHeaders（userName/nickname）在两个 codec 下都在帧头，不计入 body。
 */
@DisplayName("序列化尺寸对比：proto vs JSON vs JSON+deflate")
public class CodecSizeComparisonTest {

    private record Pair(byte[] json, byte[] proto) {
    }

    private record Stats(String label, int count, long jsonBytes, long protoBytes, long deflatePerMsg,
                         long deflateStream) {

        @Override
        public String toString() {
            double pctProto = 100.0 * protoBytes / jsonBytes;
            double pctPer = 100.0 * deflatePerMsg / jsonBytes;
            double pctStream = 100.0 * deflateStream / jsonBytes;
            return String.format(
                "%-28s n=%-5d json=%8dB  proto=%8dB (%5.1f%%)  deflate/条=%8dB (%5.1f%%)  deflate/流=%8dB (%5.1f%%)",
                label, count, jsonBytes, protoBytes, pctProto, deflatePerMsg, pctPer, deflateStream, pctStream);
        }
    }

    @Test
    @DisplayName("真实消息量级下三种编码的字节对比")
    void compareSizes() {
        List<Pair> reqs = buildOutboundC2CCorpus(300);
        List<Pair> records = buildMessageRecordCorpus(400);

        System.out.println("\n=== C2C 上行请求 body（对应 protocol.encode 发送路径，含 varHeaders 之外全部字段） ===");
        System.out.println(stats("文本", reqs, 0, reqs.size() / 2));
        System.out.println(stats("媒体", reqs, reqs.size() / 2, reqs.size()));
        System.out.println(stats("整体(REQ)", reqs, 0, reqs.size()));

        System.out.println("\n=== 消息记录/下行通知 body（C2CNotify / PULL_RESP 历史行：7 字段记录） ===");
        System.out.println(stats("文本", records, 0, records.size() * 3 / 4));
        System.out.println(stats("媒体", records, records.size() * 3 / 4, records.size()));
        System.out.println(stats("整体(记录)", records, 0, records.size()));

        System.out.println();
    }

    @Test
    @DisplayName("断言：protobuf 与流式 deflate 在整体语料上严格小于 JSON")
    void assertOrdering() {
        List<Pair> records = buildMessageRecordCorpus(400);
        Stats s = stats("整体", records, 0, records.size());
        assertTrue(s.protoBytes() < s.jsonBytes(), "protobuf 应小于 JSON: " + s);
        assertTrue(s.deflateStream() < s.jsonBytes(), "流式 deflate 应小于 JSON: " + s);
    }

    // ------------------------------------------------------------------
    // 语料生成
    // ------------------------------------------------------------------

    private static final long ME = 1812248515512705024L;
    private static final long PEER = 1812248515512705127L;
    private static final long BASE_MSG_ID = 3345678901234567801L;

    private static final String[] SENTENCES = {
        "晚上一起吃饭吗？",
        "好的，七点老地方见",
        "收到，我马上过去",
        "嗯嗯，好的，回头聊",
        "你好呀，请问在吗？",
        "图片我看到了，拍得不错",
        "文档发你邮箱了，记得查收",
        "这个链接打不开，你再发一次",
        "项目周五要上线，抓紧联调",
        "我把周报更新好了，你看看",
        "会议改到明天下午三点，别迟到",
        "那条语音我听了，没问题",
    };

    /**
     * 上行 C2C_REQ 语料，镜像 client._send() 的 body：
     * {senderId, recipientId, message:{msgType, content}}
     * proto 用 chat.C2CReq（sender_id/recipient_id + MessageContent）。
     */
    private List<Pair> buildOutboundC2CCorpus(int n) {
        Random rnd = new Random(42);
        List<Pair> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long mid = BASE_MSG_ID + i;
            int msgType = (i % 4 == 3) ? 2 : 1;
            String content = msgType == 1 ? randomText(rnd, i) : mediaContentJson(rnd, false);

            JsonObject message = new JsonObject().put("msgType", msgType).put("content", content);
            JsonObject body = new JsonObject()
                .put("senderId", String.valueOf(ME))
                .put("recipientId", String.valueOf(PEER))
                .put("message", message);
            byte[] json = body.encode().getBytes(StandardCharsets.UTF_8);

            CommonProto.MessageContent mc = CommonProto.MessageContent.newBuilder()
                .setMsgType(toProtoMsgType(msgType))
                .setContent(ByteString.copyFrom(content, StandardCharsets.UTF_8))
                .build();
            byte[] proto = ChatProto.C2CReq.newBuilder()
                .setSenderId(ME)
                .setRecipientId(PEER)
                .setMessage(mc)
                .build()
                .toByteArray();

            out.add(new Pair(json, proto));
        }
        return out;
    }

    /**
     * 消息记录语料，镜像后端落库/回推的 7 字段记录：
     * {id, senderId, recipientId, msgType, content, seq, createdAt}
     * proto 用 chat.C2CNotify（sender_id/recipient_id/message/seq/message_id 最接近）。
     */
    private List<Pair> buildMessageRecordCorpus(int n) {
        Random rnd = new Random(7);
        List<Pair> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long mid = BASE_MSG_ID + i;
            boolean isSelf = i % 2 == 0;
            long sender = isSelf ? ME : PEER;
            long recipient = isSelf ? PEER : ME;
            int msgType;
            String content;
            switch (i % 4) {
                case 0: msgType = 1; content = randomText(rnd, i); break;
                case 1: msgType = 1; content = longTextWithUrl(rnd); break;
                case 2: msgType = 3; content = mediaContentJson(rnd, true); break;
                default: msgType = 2; content = mediaContentJson(rnd, true); break;
            }
            long seq = 800000L + i * 3L;
            long createdAt = 1756800000000L + i * 1000L;

            JsonObject body = new JsonObject()
                .put("id", String.valueOf(mid))
                .put("senderId", String.valueOf(sender))
                .put("recipientId", String.valueOf(recipient))
                .put("msgType", msgType)
                .put("content", content)
                .put("seq", seq)
                .put("createdAt", createdAt);
            byte[] json = body.encode().getBytes(StandardCharsets.UTF_8);

            CommonProto.MessageContent mc = CommonProto.MessageContent.newBuilder()
                .setMsgType(toProtoMsgType(msgType))
                .setContent(ByteString.copyFrom(content, StandardCharsets.UTF_8))
                .setTimestamp(createdAt)
                .build();
            byte[] proto = ChatProto.C2CNotify.newBuilder()
                .setSenderId(sender)
                .setRecipientId(recipient)
                .setMessage(mc)
                .setSeq(seq)
                .setMessageId(mid)
                .build()
                .toByteArray();

            out.add(new Pair(json, proto));
        }
        return out;
    }

    private static String randomText(Random rnd, int i) {
        // 约 1/4 是短句（2-6 字量级），其余拼 2-5 句形成中长文本
        if (i % 4 == 0) {
            String[] shortOnes = {"在吗", "好", "嗯嗯", "收到", "OK", "来", "走吧", "👌"};
            return shortOnes[i % shortOnes.length];
        }
        int k = 2 + rnd.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < k; j++) {
            sb.append(SENTENCES[rnd.nextInt(SENTENCES.length)]);
        }
        return sb.toString();
    }

    private static String longTextWithUrl(Random rnd) {
        String url = "https://pomelo.dev/s/" + (100000 + rnd.nextInt(900000))
            + "?utm_source=im&from=shared";
        return SENTENCES[rnd.nextInt(SENTENCES.length)] + " 链接："
            + url + "，你点开看看。";
    }

    private static String mediaContentJson(Random rnd, boolean withUrl) {
        String key = "media/2026/09/" + (BASE_MSG_ID + rnd.nextInt(100000)) + ".png";
        JsonObject media = new JsonObject()
            .put("key", key)
            .put("fileName", "screenshot_" + rnd.nextInt(1000) + ".png")
            .put("size", 200_000 + rnd.nextInt(3_000_000))
            .put("width", 640 + rnd.nextInt(1000))
            .put("height", 480 + rnd.nextInt(800))
            .put("format", "png");
        if (withUrl) {
            String host = "http://localhost:9000/im";
            media.put("url", host + "/" + key
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=minioadmin%2F20260903"
                + "%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Expires=3600&X-Amz-Signature="
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        }
        return media.encode();
    }

    private static CommonProto.MsgType toProtoMsgType(int msgType) {
        switch (msgType) {
            case 1: return CommonProto.MsgType.MSG_TYPE_TEXT;
            case 2: return CommonProto.MsgType.MSG_TYPE_IMAGE;
            case 3: return CommonProto.MsgType.MSG_TYPE_VOICE;
            default: return CommonProto.MsgType.MSG_TYPE_FILE;
        }
    }

    // ------------------------------------------------------------------
    // 压缩与统计
    // ------------------------------------------------------------------

    private Stats stats(String label, List<Pair> list, int from, int to) {
        long jsonBytes = 0;
        long protoBytes = 0;
        long deflatePerMsg = 0;
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int i = from; i < to; i++) {
            byte[] json = list.get(i).json();
            jsonBytes += json.length;
            protoBytes += list.get(i).proto().length;
            deflatePerMsg += deflate(json).length;
            joined.writeBytes(json);
            joined.write('\n');
        }
        long deflateStream = deflate(joined.toByteArray()).length;
        return new Stats(label, to - from, jsonBytes, protoBytes, deflatePerMsg, deflateStream);
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            bos.write(buf, 0, n);
        }
        deflater.end();
        return bos.toByteArray();
    }
}
