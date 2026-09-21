package com.github.moxib.pomelo.logic.infrastructure;

import com.github.moxib.pomelo.logic.model.GroupInfo;
import com.github.moxib.pomelo.logic.model.GroupMemberRecord;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PgGroupRepository implements GroupRepository {

  private static final Logger LOG = LoggerFactory.getLogger(PgGroupRepository.class);

  private static final String CREATE_GROUP_SQL = """
    INSERT INTO im_group (id, name, avatar, description, owner_id, max_members, created_at, updated_at)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
    """;

  private static final String FIND_GROUP_SQL = """
    SELECT id, name, avatar, description, owner_id,
           (SELECT COUNT(*) FROM im_group_member WHERE group_id = g.id) AS member_count,
           max_members, created_at, updated_at
    FROM im_group g WHERE id = $1
    """;

  private static final String FIND_GROUPS_BY_USER_SQL = """
    SELECT g.id, g.name, g.avatar, g.description, g.owner_id,
           (SELECT COUNT(*) FROM im_group_member WHERE group_id = g.id) AS member_count,
           g.max_members, g.created_at, g.updated_at
    FROM im_group g
    JOIN im_group_member gm ON gm.group_id = g.id
    WHERE gm.user_id = $1
    ORDER BY g.updated_at DESC
    """;

  private static final String FIND_MEMBERS_SQL = """
    SELECT gm.group_id, gm.user_id, u.user_name, u.nickname, u.avatar, gm.role, gm.joined_at
    FROM im_group_member gm
    JOIN im_user u ON gm.user_id = u.id
    WHERE gm.group_id = $1
    ORDER BY gm.role DESC, gm.joined_at
    """;

  private static final String ADD_MEMBER_SQL = """
    INSERT INTO im_group_member (id, group_id, user_id, role, joined_at)
    VALUES ($1, $2, $3, $4, $5) ON CONFLICT (group_id, user_id) DO NOTHING
    """;

  private static final String REMOVE_MEMBER_SQL = """
    DELETE FROM im_group_member WHERE group_id = $1 AND user_id = $2
    """;

  // CTE 原子转让：仅当 owner 匹配才更新 owner_id；随后调整双方角色（原群主→成员、新群主→群主）。
  // 外层 rowCount 为角色更新的行数（1~2），0 表示群不存在或 owner 不匹配。
  private static final String TRANSFER_OWNERSHIP_SQL = """
    WITH g AS (
      UPDATE im_group SET owner_id = $3, updated_at = $4
      WHERE id = $1 AND owner_id = $2
      RETURNING id
    )
    UPDATE im_group_member gm
    SET role = CASE WHEN gm.user_id = $2 THEN 0 ELSE 2 END
    FROM g
    WHERE gm.group_id = $1 AND gm.user_id IN ($2, $3)
    """;

  // CTE 原子解散：仅当 owner 匹配才删群，随后删除全部成员关系；历史消息保留。
  // 外层 rowCount 为删除的成员关系行数（群主必在成员中，成功时 >= 1），0 表示群不存在或 owner 不匹配。
  private static final String DISSOLVE_GROUP_SQL = """
    WITH g AS (
      DELETE FROM im_group WHERE id = $1 AND owner_id = $2 RETURNING id
    )
    DELETE FROM im_group_member WHERE group_id = $1 AND EXISTS (SELECT 1 FROM g)
    """;

  private static final String IS_MEMBER_SQL = """
    SELECT 1 FROM im_group_member WHERE group_id = $1 AND user_id = $2
    """;

  private static final String IS_MUTED_SQL = """
    SELECT muted_until > EXTRACT(EPOCH FROM NOW()) * 1000 AS muted
    FROM im_group_member WHERE group_id = $1 AND user_id = $2
    """;

  // 合并查询：单次 SQL 获取群上下文（替代 findById + isMember + isMuted 三次查询）
  private static final String GET_MEMBER_CTX_SQL = """
    SELECT g.name,
           CASE WHEN gm.user_id IS NOT NULL THEN true ELSE false END AS is_member,
           COALESCE(gm.muted_until > EXTRACT(EPOCH FROM NOW()) * 1000, false) AS is_muted
    FROM im_group g
    LEFT JOIN im_group_member gm ON gm.group_id = g.id AND gm.user_id = $2
    WHERE g.id = $1
    """;

  private static final String IS_FRIEND_SQL = """
    SELECT 1 FROM im_friend
    WHERE ((user_id = $1 AND friend_id = $2) OR (user_id = $2 AND friend_id = $1))
    AND status = 1
    """;

  private static final String UPDATE_LAST_READ_SQL = """
    UPDATE im_group_member SET last_read_seq = GREATEST(last_read_seq, $1)
    WHERE group_id = $2 AND user_id = $3
    """;

  // 幂等唯一键 uq_group_client_msg (group_id, sender_id, client_msg_id) 由分区键 group_id 参与构成，
  // 重试冲突时 DO NOTHING，rowCount=0 由调用方查回原消息
  private static final String SAVE_MSG_SQL = """
    INSERT INTO im_message_group (id, sender_id, group_id, msg_type, content, ext, seq, created_at, client_msg_id)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) ON CONFLICT (group_id, sender_id, client_msg_id) DO NOTHING
    """;

  private static final String FIND_BY_GROUP_SENDER_CLIENT_MSG_SQL = """
    SELECT id, sender_id, group_id, msg_type, content, ext, seq, created_at
    FROM im_message_group WHERE group_id = $1 AND sender_id = $2 AND client_msg_id = $3
    ORDER BY id DESC LIMIT 1
    """;

  private static final String FIND_MEMBER_READ_STATES_SQL = """
    SELECT user_id, last_read_seq FROM im_group_member WHERE group_id = $1
    """;

  private static final String FIND_MSG_READERS_SQL = """
    SELECT u.id, u.nickname, u.avatar
    FROM im_group_member gm
    JOIN im_user u ON gm.user_id = u.id
    WHERE gm.group_id = $1 AND gm.last_read_seq >= $2
    ORDER BY u.nickname
    """;

  private static final String PULL_MSG_BACKWARD_SQL = """
    SELECT msg.id, msg.sender_id, msg.group_id, msg.msg_type, msg.content, msg.ext, msg.seq, msg.created_at
    FROM im_message_group msg
    WHERE msg.group_id = $1 AND msg.seq < $2 ORDER BY msg.seq DESC LIMIT $3
    """;

  private static final String PULL_MSG_FORWARD_SQL = """
    SELECT msg.id, msg.sender_id, msg.group_id, msg.msg_type, msg.content, msg.ext, msg.seq, msg.created_at
    FROM im_message_group msg
    WHERE msg.group_id = $1 AND msg.seq > $2 ORDER BY msg.seq LIMIT $3
    """;

  private final Pool pool;

  public PgGroupRepository(Vertx vertx) {
    this.pool = PgPoolFactory.get(vertx);
  }

  @Override
  public Future<Void> createGroup(GroupInfo group) {
    return pool.preparedQuery(CREATE_GROUP_SQL)
      .execute(Tuple.of(group.getId(), group.getName(), group.getAvatar(),
        group.getDescription(), group.getOwnerId(), group.getMaxMembers(),
        group.getCreatedAt(), group.getUpdatedAt()))
      .onSuccess(r -> LOG.info("群创建成功: id={} name={}", group.getId(), group.getName()))
      .onFailure(e -> LOG.error("群创建失败 id={}: {}", group.getId(), e.getMessage()))
      .mapEmpty();
  }

  @Override
  public Future<GroupInfo> findById(long id) {
    return pool.preparedQuery(FIND_GROUP_SQL)
      .execute(Tuple.of(id))
      .map(rows -> rows.size() == 0 ? null : rowToGroupInfo(rows.iterator().next()));
  }

  @Override
  public Future<List<GroupInfo>> findGroupsByUserId(long userId) {
    return pool.preparedQuery(FIND_GROUPS_BY_USER_SQL)
      .execute(Tuple.of(userId))
      .map(rows -> {
        List<GroupInfo> list = new ArrayList<>();
        for (Row row : rows) list.add(rowToGroupInfo(row));
        return list;
      });
  }

  @Override
  public Future<List<GroupMemberRecord>> findMembers(long groupId) {
    return pool.preparedQuery(FIND_MEMBERS_SQL)
      .execute(Tuple.of(groupId))
      .map(rows -> {
        List<GroupMemberRecord> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(GroupMemberRecord.builder()
            .groupId(row.getLong("group_id"))
            .userId(row.getLong("user_id"))
            .userName(row.getString("user_name"))
            .nickname(row.getString("nickname"))
            .avatar(row.getString("avatar"))
            .role(row.getInteger("role"))
            .joinedAt(row.getLong("joined_at"))
            .build());
        }
        return list;
      });
  }

  @Override
  public Future<Void> addMember(long id, long groupId, long userId, int role, long now) {
    return pool.preparedQuery(ADD_MEMBER_SQL)
      .execute(Tuple.of(id, groupId, userId, role, now))
      .onSuccess(r -> LOG.debug("成员加入: groupId={} userId={}", groupId, userId))
      .onFailure(e -> LOG.error("成员加入失败: {}", e.getMessage()))
      .mapEmpty();
  }

  @Override
  public Future<Void> removeMember(long groupId, long userId) {
    return pool.preparedQuery(REMOVE_MEMBER_SQL)
      .execute(Tuple.of(groupId, userId))
      .onSuccess(r -> LOG.debug("成员移除: groupId={} userId={}", groupId, userId))
      .onFailure(e -> LOG.error("成员移除失败: {}", e.getMessage()))
      .mapEmpty();
  }

  @Override
  public Future<Integer> transferOwnership(long groupId, long fromOwnerId, long toUserId) {
    return pool.preparedQuery(TRANSFER_OWNERSHIP_SQL)
      .execute(Tuple.of(groupId, fromOwnerId, toUserId, System.currentTimeMillis()))
      .map(rows -> rows.rowCount())
      .onSuccess(count -> LOG.info("群主转让: groupId={} from={} to={} rows={}", groupId, fromOwnerId, toUserId, count))
      .onFailure(e -> LOG.error("群主转让失败: groupId={} {}", groupId, e.getMessage()));
  }

  @Override
  public Future<Integer> dissolveGroup(long groupId, long ownerId) {
    return pool.preparedQuery(DISSOLVE_GROUP_SQL)
      .execute(Tuple.of(groupId, ownerId))
      .map(rows -> rows.rowCount())
      .onSuccess(count -> LOG.info("群聊解散: groupId={} owner={} membersRemoved={}", groupId, ownerId, count))
      .onFailure(e -> LOG.error("群聊解散失败: groupId={} {}", groupId, e.getMessage()));
  }

  @Override
  public Future<Boolean> isMember(long groupId, long userId) {
    return pool.preparedQuery(IS_MEMBER_SQL)
      .execute(Tuple.of(groupId, userId))
      .map(rows -> rows.size() > 0);
  }

  @Override
  public Future<Boolean> isMuted(long groupId, long userId) {
    return pool.preparedQuery(IS_MUTED_SQL)
      .execute(Tuple.of(groupId, userId))
      .map(rows -> {
        if (rows.size() == 0) return false;
        return rows.iterator().next().getBoolean("muted");
      });
  }

  @Override
  public Future<GroupMemberContext> getGroupMemberContext(long groupId, long userId) {
    return pool.preparedQuery(GET_MEMBER_CTX_SQL)
      .execute(Tuple.of(groupId, userId))
      .map(rows -> {
        if (rows.size() == 0) {
          return new GroupMemberContext(false, null, false, false);
        }
        Row row = rows.iterator().next();
        return new GroupMemberContext(true,
          row.getString("name"),
          row.getBoolean("is_member"),
          row.getBoolean("is_muted"));
      });
  }

  @Override
  public Future<Boolean> isFriend(long userId1, long userId2) {
    return pool.preparedQuery(IS_FRIEND_SQL)
      .execute(Tuple.of(userId1, userId2))
      .map(rows -> rows.size() > 0);
  }

  @Override
  public Future<Void> updateLastReadSeq(long groupId, long userId, long seq) {
    return pool.preparedQuery(UPDATE_LAST_READ_SQL)
      .execute(Tuple.of(seq, groupId, userId))
      .mapEmpty();
  }

  @Override
  public Future<Boolean> saveMessage(long id, long groupId, long senderNumericId,
                                     int msgType, String content, String ext, long seq, long createdAt, long clientMsgId) {
    return pool.preparedQuery(SAVE_MSG_SQL)
      .execute(Tuple.of(id, senderNumericId, groupId, msgType, content, ext, seq, createdAt, clientMsgId))
      .map(r -> {
        boolean inserted = r.rowCount() > 0;
        if (inserted) {
          LOG.debug("群消息持久化: id={} groupId={} seq={}", id, groupId, seq);
        }
        return inserted;
      })
      .onFailure(e -> LOG.error("群消息持久化失败 id={}: {}", id, e.getMessage()));
  }

  @Override
  public Future<GroupMsgWithSender> findByGroupSenderAndClientMsgId(long groupId, long senderId, long clientMsgId) {
    if (clientMsgId <= 0) {
      return Future.succeededFuture(null);
    }
    return pool.preparedQuery(FIND_BY_GROUP_SENDER_CLIENT_MSG_SQL)
      .execute(Tuple.of(groupId, senderId, clientMsgId))
      .map(rows -> {
        if (rows.size() == 0) {
          return null;
        }
        Row row = rows.iterator().next();
        return new GroupMsgWithSender(
          row.getLong("id"),
          row.getLong("sender_id"),
          row.getLong("group_id"),
          row.getInteger("msg_type"),
          row.getString("content"),
          row.getString("ext"),
          row.getLong("seq"),
          row.getLong("created_at"));
      });
  }

  @Override
  public Future<Map<Long, Long>> findMemberReadStates(long groupId) {
    return pool.preparedQuery(FIND_MEMBER_READ_STATES_SQL)
      .execute(Tuple.of(groupId))
      .map(rows -> {
        Map<Long, Long> out = new HashMap<>();
        for (Row row : rows) {
          out.put(row.getLong("user_id"), row.getLong("last_read_seq"));
        }
        return out;
      });
  }

  @Override
  public Future<List<GroupMsgReader>> findMsgReaders(long groupId, long seq) {
    return pool.preparedQuery(FIND_MSG_READERS_SQL)
      .execute(Tuple.of(groupId, seq))
      .map(rows -> {
        List<GroupMsgReader> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(new GroupMsgReader(
            row.getLong("id"),
            row.getString("nickname"),
            row.getString("avatar")));
        }
        return list;
      });
  }

  @Override
  public Future<List<GroupMsgWithSender>> pullMessages(long groupId, long cursor, int limit, boolean backward) {
    String sql = backward ? PULL_MSG_BACKWARD_SQL : PULL_MSG_FORWARD_SQL;
    return pool.preparedQuery(sql)
      .execute(Tuple.of(groupId, cursor, limit))
      .map(rows -> {
        List<GroupMsgWithSender> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(new GroupMsgWithSender(
            row.getLong("id"), row.getLong("sender_id"), row.getLong("group_id"),
            row.getInteger("msg_type"), row.getString("content"), row.getString("ext"),
            row.getLong("seq"), row.getLong("created_at")));
        }
        return list;
      });
  }

  private GroupInfo rowToGroupInfo(Row row) {
    return GroupInfo.builder()
      .id(row.getLong("id"))
      .name(row.getString("name"))
      .avatar(row.getString("avatar"))
      .description(row.getString("description"))
      .ownerId(row.getLong("owner_id"))
      .memberCount(row.getInteger("member_count"))
      .maxMembers(row.getInteger("max_members"))
      .createdAt(row.getLong("created_at"))
      .updatedAt(row.getLong("updated_at"))
      .build();
  }
}
