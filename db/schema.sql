-- ============================================================================
-- Pomelo IM 数据库初始化脚本
-- PostgreSQL 17
-- ============================================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS im_user (
    id         BIGINT       PRIMARY KEY,           -- Snowflake 全局唯一 ID
    user_id    VARCHAR(64)  UNIQUE NOT NULL,       -- NanoID 系统生成（对外唯一标识）
    user_name  VARCHAR(64)  UNIQUE NOT NULL,       -- 用户名（登录凭证）
    nickname   VARCHAR(128) NOT NULL,
    avatar     VARCHAR(512),                      -- 头像URL
    password   VARCHAR(256) NOT NULL,             -- bcrypt hash
    status     SMALLINT     NOT NULL DEFAULT 0,   -- 0=离线, 1=在线
    created_at BIGINT       NOT NULL,             -- Unix毫秒
    updated_at BIGINT       NOT NULL
);

COMMENT ON TABLE  im_user              IS '用户表';
COMMENT ON COLUMN im_user.id           IS 'Snowflake 全局唯一 ID';
COMMENT ON COLUMN im_user.user_id      IS 'NanoID 系统生成（对外唯一标识）';
COMMENT ON COLUMN im_user.user_name    IS '用户名（登录凭证）';
COMMENT ON COLUMN im_user.nickname     IS '昵称';
COMMENT ON COLUMN im_user.avatar       IS '头像URL';
COMMENT ON COLUMN im_user.password     IS '密码hash (bcrypt)';
COMMENT ON COLUMN im_user.status       IS '0=离线, 1=在线';
COMMENT ON COLUMN im_user.created_at   IS '创建时间 (Unix毫秒)';
COMMENT ON COLUMN im_user.updated_at   IS '更新时间 (Unix毫秒)';

CREATE INDEX idx_user_status ON im_user(status);


-- 2. 群组元数据表
--    id: 雪花ID，服务端生成
CREATE TABLE IF NOT EXISTS im_group (
    id           BIGINT       PRIMARY KEY,              -- 雪花ID (服务端生成)
    name         VARCHAR(128) NOT NULL,                 -- 群名
    avatar       VARCHAR(512),                         -- 群头像 URL
    description  TEXT,                                  -- 群公告/简介
    owner_id     VARCHAR(64) NOT NULL,                  -- 群主 userId (NanoID)
    max_members  INT         NOT NULL DEFAULT 200,      -- 群成员上限
    created_at   BIGINT      NOT NULL,                  -- 创建时间 (Unix毫秒)
    updated_at   BIGINT      NOT NULL                   -- 更新时间 (Unix毫秒)
);

COMMENT ON TABLE  im_group              IS '群组元数据表';
COMMENT ON COLUMN im_group.id           IS '雪花ID (服务端生成)';
COMMENT ON COLUMN im_group.name         IS '群名';
COMMENT ON COLUMN im_group.avatar       IS '群头像URL';
COMMENT ON COLUMN im_group.description  IS '群公告/简介';
COMMENT ON COLUMN im_group.owner_id     IS '群主 userId (NanoID)';
COMMENT ON COLUMN im_group.max_members  IS '群成员上限';
COMMENT ON COLUMN im_group.created_at   IS '创建时间 (Unix毫秒)';
COMMENT ON COLUMN im_group.updated_at   IS '更新时间 (Unix毫秒)';


-- 3. 单聊消息表
--    id  : 雪花ID，客户端生成，作为消息主键
--    seq : RedisIdGenerator 全局ID，服务端分配，全局单调递增
--    content : 文本消息存原文，图片/视频/文件存资源链接
CREATE TABLE IF NOT EXISTS im_message_c2c (
    id              BIGINT       NOT NULL,           -- 雪花ID (客户端生成)
    sender_id       BIGINT       NOT NULL,           -- 发送者 ID (im_user.id)
    recipient_id    BIGINT       NOT NULL,           -- 接收者 ID (im_user.id)
    conversation_id VARCHAR(48)  NOT NULL,           -- 会话ID (min_id:max_id 格式, 如 "337432242848534528:337432242848534529")
    msg_type        SMALLINT     NOT NULL,           -- 1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system
    content         TEXT,                            -- 文本内容或资源链接
    seq             BIGINT       NOT NULL,           -- seqsvr 收件人同步版本号 (写扩散信箱递增序号)
    status          SMALLINT     NOT NULL DEFAULT 0, -- 0=已发送, 1=已送达, 2=已读
    created_at      BIGINT       NOT NULL,           -- 创建时间 (Unix毫秒)
    PRIMARY KEY (id)
) PARTITION BY HASH (id);

COMMENT ON TABLE  im_message_c2c              IS '单聊消息表 (HASH分区)';
COMMENT ON COLUMN im_message_c2c.id           IS '雪花ID (客户端生成)';
COMMENT ON COLUMN im_message_c2c.sender_id    IS '发送者 im_user.id';
COMMENT ON COLUMN im_message_c2c.recipient_id    IS '接收者 im_user.id';
COMMENT ON COLUMN im_message_c2c.conversation_id IS '会话ID (min_id:max_id, 如 123:456)';
COMMENT ON COLUMN im_message_c2c.msg_type        IS '1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system';
COMMENT ON COLUMN im_message_c2c.content      IS '文本内容或资源链接 (文本直接存, 图片/视频/文件存URL)';
COMMENT ON COLUMN im_message_c2c.seq          IS 'seqsvr 收件人同步版本号（写扩散信箱递增序号，收件人维度）；会话内排序请用 created_at';
COMMENT ON COLUMN im_message_c2c.status       IS '0=已发送, 1=已送达, 2=已读';
COMMENT ON COLUMN im_message_c2c.created_at   IS '创建时间 (Unix毫秒)';

CREATE TABLE im_message_c2c_p0 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 0);
CREATE TABLE im_message_c2c_p1 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 1);
CREATE TABLE im_message_c2c_p2 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 2);
CREATE TABLE im_message_c2c_p3 PARTITION OF im_message_c2c FOR VALUES WITH (modulus 4, remainder 3);

-- 会话维度查询索引（双向会话，按 conversation_id 排序）
CREATE INDEX idx_c2c_conversation ON im_message_c2c (conversation_id, created_at DESC);
-- 离线拉取索引（收件人信箱按 seq 增量有序拉取；seq 为收件人同步版本号）
CREATE INDEX idx_c2c_recipient_pending ON im_message_c2c (recipient_id, seq) WHERE status < 2;
-- BRIN 索引：全局时间范围扫描，体积极小
CREATE INDEX idx_c2c_created_at_brin ON im_message_c2c USING BRIN (created_at);


-- 4. 群聊消息表
CREATE TABLE IF NOT EXISTS im_message_group (
    id         BIGINT   NOT NULL,               -- 雪花ID (客户端生成)
    sender_id  BIGINT   NOT NULL,               -- 发送者 im_user.id
    group_id   BIGINT   NOT NULL,               -- im_group.id (雪花ID)
    msg_type   SMALLINT NOT NULL,               -- 1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system
    content    TEXT,                            -- 文本内容或资源链接
    seq        BIGINT   NOT NULL,               -- 群同步版本号
    created_at BIGINT   NOT NULL,               -- 创建时间 (Unix毫秒)
    PRIMARY KEY (id)
) PARTITION BY HASH (id);

COMMENT ON TABLE  im_message_group             IS '群聊消息表 (HASH分区)';
COMMENT ON COLUMN im_message_group.id          IS '雪花ID (客户端生成)';
COMMENT ON COLUMN im_message_group.sender_id   IS '发送者ID';
COMMENT ON COLUMN im_message_group.group_id    IS '群组ID';
COMMENT ON COLUMN im_message_group.msg_type    IS '1=text, 2=image, 3=voice, 4=video, 5=file, 6=emoji, 7=system';
COMMENT ON COLUMN im_message_group.content     IS '文本内容或资源链接 (文本直接存, 图片/视频/文件存URL)';
COMMENT ON COLUMN im_message_group.seq         IS '群同步版本号（设计保留，群消息暂未接入；群内排序建议按 created_at）';
COMMENT ON COLUMN im_message_group.created_at  IS '创建时间 (Unix毫秒)';

CREATE TABLE im_message_group_p0 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 0);
CREATE TABLE im_message_group_p1 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 1);
CREATE TABLE im_message_group_p2 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 2);
CREATE TABLE im_message_group_p3 PARTITION OF im_message_group FOR VALUES WITH (modulus 4, remainder 3);

CREATE INDEX idx_group_conversation ON im_message_group (group_id, created_at DESC);
CREATE INDEX idx_group_created_at_brin ON im_message_group USING BRIN (created_at);
CREATE INDEX idx_group_seq ON im_message_group (seq);


-- 5. 群组成员表
--    id: 雪花ID，服务端分配，作为成员关系主键
--    group_id: im_group.id (雪花ID)
--    user_id: im_user.id (雪花ID)
CREATE TABLE IF NOT EXISTS im_group_member (
    id            BIGINT   NOT NULL,                    -- 雪花ID (服务端生成)
    group_id      BIGINT   NOT NULL,                    -- im_group.id
    user_id       BIGINT   NOT NULL,                    -- im_user.id
    role          SMALLINT NOT NULL DEFAULT 0,          -- 0=成员, 1=管理员, 2=群主
    last_read_seq BIGINT   NOT NULL DEFAULT 0,          -- 已读游标
    muted_until   BIGINT   NOT NULL DEFAULT 0,          -- 禁言截止时间戳 (Unix毫秒)，0=未禁言
    joined_at     BIGINT   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (group_id, user_id)
);

COMMENT ON TABLE  im_group_member              IS '群组成员表';
COMMENT ON COLUMN im_group_member.id            IS '雪花ID (服务端生成)';
COMMENT ON COLUMN im_group_member.group_id      IS 'im_group.id (雪花ID)';
COMMENT ON COLUMN im_group_member.user_id       IS 'im_user.id (雪花ID)';
COMMENT ON COLUMN im_group_member.role          IS '0=成员, 1=管理员, 2=群主';
COMMENT ON COLUMN im_group_member.last_read_seq IS '已读游标 — 该成员在此群读到的最后一条消息 seq';
COMMENT ON COLUMN im_group_member.muted_until   IS '禁言截止时间戳 (Unix毫秒)，0=未禁言';
COMMENT ON COLUMN im_group_member.joined_at     IS '加入时间 (Unix毫秒)';

CREATE INDEX idx_group_member_user ON im_group_member (user_id);


-- 6. 好友关系表
CREATE TABLE IF NOT EXISTS im_friend (
    user_id    BIGINT    NOT NULL,              -- 用户 im_user.id
    friend_id  BIGINT    NOT NULL,              -- 好友 im_user.id
    status     SMALLINT  NOT NULL DEFAULT 0,   -- 0=待接受, 1=已接受
    created_at BIGINT    NOT NULL,
    PRIMARY KEY (user_id, friend_id)
);

COMMENT ON TABLE  im_friend             IS '好友关系表';
COMMENT ON COLUMN im_friend.user_id     IS '用户 im_user.id';
COMMENT ON COLUMN im_friend.friend_id   IS '好友 im_user.id';
COMMENT ON COLUMN im_friend.status      IS '0=待接受, 1=已接受';
COMMENT ON COLUMN im_friend.created_at  IS '创建时间 (Unix毫秒)';

CREATE INDEX idx_friend_user ON im_friend (user_id);
