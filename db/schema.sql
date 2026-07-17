-- ============================================================================
-- Pomelo IM 数据库初始化脚本
-- PostgreSQL 17
-- ============================================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS im_user (
    user_id    VARCHAR(64)  PRIMARY KEY,
    nickname   VARCHAR(128) NOT NULL,
    avatar     VARCHAR(512),                      -- 头像URL
    password   VARCHAR(256) NOT NULL,             -- bcrypt hash
    status     SMALLINT     NOT NULL DEFAULT 0,   -- 0=离线, 1=在线
    created_at BIGINT       NOT NULL,             -- Unix毫秒
    updated_at BIGINT       NOT NULL
);

COMMENT ON TABLE  im_user              IS '用户表';
COMMENT ON COLUMN im_user.user_id      IS '用户ID';
COMMENT ON COLUMN im_user.nickname     IS '昵称';
COMMENT ON COLUMN im_user.avatar       IS '头像URL';
COMMENT ON COLUMN im_user.password     IS '密码hash (bcrypt)';
COMMENT ON COLUMN im_user.status       IS '0=离线, 1=在线';
COMMENT ON COLUMN im_user.created_at   IS '创建时间 (Unix毫秒)';
COMMENT ON COLUMN im_user.updated_at   IS '更新时间 (Unix毫秒)';

CREATE INDEX idx_user_status ON im_user(status);


-- 2. 单聊消息表
--    id  : 雪花ID，客户端生成，作为消息主键
--    seq : RedisIdGenerator 全局ID，服务端分配，全局单调递增
--    content : 文本消息存原文，图片/视频/文件存资源链接
CREATE TABLE IF NOT EXISTS im_message_c2c (
    id           BIGINT       NOT NULL,           -- 雪花ID (客户端生成)
    sender_id    VARCHAR(64)  NOT NULL,           -- 发送者ID
    recipient_id VARCHAR(64)  NOT NULL,           -- 接收者ID
    msg_type     SMALLINT     NOT NULL,           -- 1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system
    content      TEXT,                            -- 文本内容或资源链接
    seq          BIGINT       NOT NULL,           -- RedisIdGenerator 全局ID (服务端分配)
    status       SMALLINT     NOT NULL DEFAULT 0, -- 0=已发送, 1=已送达, 2=已读
    created_at   BIGINT       NOT NULL,           -- 创建时间 (Unix毫秒)
    PRIMARY KEY (id)
) PARTITION BY HASH (id);

COMMENT ON TABLE  im_message_c2c              IS '单聊消息表 (HASH分区)';
COMMENT ON COLUMN im_message_c2c.id           IS '雪花ID (客户端生成)';
COMMENT ON COLUMN im_message_c2c.sender_id    IS '发送者ID';
COMMENT ON COLUMN im_message_c2c.recipient_id IS '接收者ID';
COMMENT ON COLUMN im_message_c2c.msg_type     IS '1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system';
COMMENT ON COLUMN im_message_c2c.content      IS '文本内容或资源链接 (文本直接存, 图片/视频/文件存URL)';
COMMENT ON COLUMN im_message_c2c.seq          IS 'RedisIdGenerator 全局ID (服务端分配, 全局单调递增)';
COMMENT ON COLUMN im_message_c2c.status       IS '0=已发送, 1=已送达, 2=已读';
COMMENT ON COLUMN im_message_c2c.created_at   IS '创建时间 (Unix毫秒)';

CREATE TABLE im_message_c2c_p0 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 0);
CREATE TABLE im_message_c2c_p1 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 1);
CREATE TABLE im_message_c2c_p2 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 2);
CREATE TABLE im_message_c2c_p3 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 3);

-- 核心查询索引：按接收者+发送者+时间拉取会话消息
CREATE INDEX idx_c2c_conversation ON im_message_c2c (recipient_id, sender_id, created_at DESC);
-- BRIN 索引：全局时间范围扫描，体积极小
CREATE INDEX idx_c2c_created_at_brin ON im_message_c2c USING BRIN (created_at);
-- 按 seq 排序拉取（全局有序）
CREATE INDEX idx_c2c_seq ON im_message_c2c (seq);
-- 部分索引：仅索引未完全送达的消息，用于离线拉取，体积极小
CREATE INDEX CONCURRENTLY idx_c2c_pending ON im_message_c2c (recipient_id, created_at) WHERE status < 2;


-- 3. 群聊消息表
CREATE TABLE IF NOT EXISTS im_message_group (
    id         BIGINT       NOT NULL,             -- 雪花ID (客户端生成)
    sender_id  VARCHAR(64)  NOT NULL,             -- 发送者ID
    group_id   VARCHAR(64)  NOT NULL,             -- 群组ID
    msg_type   SMALLINT     NOT NULL,             -- 1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system
    content    TEXT,                              -- 文本内容或资源链接
    seq        BIGINT       NOT NULL,             -- RedisIdGenerator 全局ID (服务端分配)
    created_at BIGINT       NOT NULL,             -- 创建时间 (Unix毫秒)
    PRIMARY KEY (id)
) PARTITION BY HASH (id);

COMMENT ON TABLE  im_message_group             IS '群聊消息表 (HASH分区)';
COMMENT ON COLUMN im_message_group.id          IS '雪花ID (客户端生成)';
COMMENT ON COLUMN im_message_group.sender_id   IS '发送者ID';
COMMENT ON COLUMN im_message_group.group_id    IS '群组ID';
COMMENT ON COLUMN im_message_group.msg_type    IS '1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system';
COMMENT ON COLUMN im_message_group.content     IS '文本内容或资源链接 (文本直接存, 图片/视频/文件存URL)';
COMMENT ON COLUMN im_message_group.seq         IS 'RedisIdGenerator 全局ID (服务端分配, 全局单调递增)';
COMMENT ON COLUMN im_message_group.created_at  IS '创建时间 (Unix毫秒)';

CREATE TABLE im_message_group_p0 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 0);
CREATE TABLE im_message_group_p1 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 1);
CREATE TABLE im_message_group_p2 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 2);
CREATE TABLE im_message_group_p3 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 3);

CREATE INDEX idx_group_conversation ON im_message_group (group_id, created_at DESC);
CREATE INDEX idx_group_created_at_brin ON im_message_group USING BRIN (created_at);
CREATE INDEX idx_group_seq ON im_message_group (seq);


-- 4. 群组成员表
CREATE TABLE IF NOT EXISTS im_group_member (
    group_id   VARCHAR(64) NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    role       SMALLINT    NOT NULL DEFAULT 0,    -- 0=成员, 1=管理员, 2=群主
    joined_at  BIGINT      NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX idx_group_member_user ON im_group_member (user_id);
