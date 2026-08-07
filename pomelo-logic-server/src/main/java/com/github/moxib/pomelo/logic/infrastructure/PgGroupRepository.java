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
import java.util.List;

public class PgGroupRepository implements GroupRepository {

  private static final Logger LOG = LoggerFactory.getLogger(PgGroupRepository.class);

  private static final String CREATE_GROUP_SQL = """
    INSERT INTO im_group (id, group_id, name, avatar, description, owner_id, max_members, created_at, updated_at)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
    """;

  private static final String FIND_GROUP_SQL = """
    SELECT id, group_id, name, avatar, description, owner_id,
           (SELECT COUNT(*) FROM im_group_member WHERE group_id = g.group_id) AS member_count,
           max_members, created_at, updated_at
    FROM im_group g WHERE id = $1
    """;

  private static final String FIND_BY_GROUP_ID_SQL = """
    SELECT id, group_id, name, avatar, description, owner_id,
           (SELECT COUNT(*) FROM im_group_member WHERE group_id = g.group_id) AS member_count,
           max_members, created_at, updated_at
    FROM im_group g WHERE group_id = $1
    """;

  private static final String FIND_GROUPS_BY_USER_SQL = """
    SELECT g.id, g.group_id, g.name, g.avatar, g.description, g.owner_id,
           (SELECT COUNT(*) FROM im_group_member WHERE group_id = g.group_id) AS member_count,
           g.max_members, g.created_at, g.updated_at
    FROM im_group g
    JOIN im_group_member gm ON g.group_id = gm.group_id
    WHERE gm.user_id = $1
    ORDER BY g.updated_at DESC
    """;

  private static final String FIND_MEMBERS_SQL = """
    SELECT gm.group_id, gm.user_id, u.user_id AS nano_id, u.user_name, u.nickname, u.avatar, gm.role, gm.joined_at
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

  private static final String IS_MEMBER_SQL = """
    SELECT 1 FROM im_group_member WHERE group_id = $1 AND user_id = $2
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

  private static final String SAVE_MSG_SQL = """
    INSERT INTO im_message_group (id, sender_id, group_id, msg_type, content, seq, created_at)
    VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (id) DO NOTHING
    """;

  private static final String PULL_MSG_BACKWARD_SQL = """
    SELECT id, sender_id, group_id, msg_type, content, seq, created_at
    FROM im_message_group WHERE group_id = $1 AND seq < $2 ORDER BY seq DESC LIMIT $3
    """;

  private static final String PULL_MSG_FORWARD_SQL = """
    SELECT id, sender_id, group_id, msg_type, content, seq, created_at
    FROM im_message_group WHERE group_id = $1 AND seq > $2 ORDER BY seq LIMIT $3
    """;

  private final Pool pool;

  public PgGroupRepository(Vertx vertx) {
    this.pool = PgPoolFactory.get(vertx);
  }

  @Override
  public Future<Void> createGroup(GroupInfo group) {
    return pool.preparedQuery(CREATE_GROUP_SQL)
      .execute(Tuple.of(group.getId(), group.getGroupId(), group.getName(), group.getAvatar(),
        group.getDescription(), group.getOwnerId(), group.getMaxMembers(),
        group.getCreatedAt(), group.getUpdatedAt()))
      .onSuccess(r -> LOG.info("群创建成功: id={} groupId={} name={}", group.getId(), group.getGroupId(), group.getName()))
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
  public Future<GroupInfo> findByGroupId(String groupId) {
    return pool.preparedQuery(FIND_BY_GROUP_ID_SQL)
      .execute(Tuple.of(groupId))
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
  public Future<List<GroupMemberRecord>> findMembers(String groupId) {
    return pool.preparedQuery(FIND_MEMBERS_SQL)
      .execute(Tuple.of(groupId))
      .map(rows -> {
        List<GroupMemberRecord> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(GroupMemberRecord.builder()
            .groupId(row.getString("group_id"))
            .userId(row.getLong("user_id"))
            .nanoId(row.getString("nano_id"))
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
  public Future<Void> addMember(long id, String groupId, long userId, int role, long now) {
    return pool.preparedQuery(ADD_MEMBER_SQL)
      .execute(Tuple.of(id, groupId, userId, role, now))
      .onSuccess(r -> LOG.debug("成员加入: groupId={} userId={}", groupId, userId))
      .onFailure(e -> LOG.error("成员加入失败: {}", e.getMessage()))
      .mapEmpty();
  }

  @Override
  public Future<Void> removeMember(String groupId, long userId) {
    return pool.preparedQuery(REMOVE_MEMBER_SQL)
      .execute(Tuple.of(groupId, userId))
      .onSuccess(r -> LOG.debug("成员移除: groupId={} userId={}", groupId, userId))
      .onFailure(e -> LOG.error("成员移除失败: {}", e.getMessage()))
      .mapEmpty();
  }

  @Override
  public Future<Boolean> isMember(String groupId, long userId) {
    return pool.preparedQuery(IS_MEMBER_SQL)
      .execute(Tuple.of(groupId, userId))
      .map(rows -> rows.size() > 0);
  }

  @Override
  public Future<Boolean> isFriend(long userId1, long userId2) {
    return pool.preparedQuery(IS_FRIEND_SQL)
      .execute(Tuple.of(userId1, userId2))
      .map(rows -> rows.size() > 0);
  }

  @Override
  public Future<Void> updateLastReadSeq(String groupId, long userId, long seq) {
    return pool.preparedQuery(UPDATE_LAST_READ_SQL)
      .execute(Tuple.of(seq, groupId, userId))
      .mapEmpty();
  }

  @Override
  public Future<Boolean> saveMessage(long id, String groupId, long senderNumericId,
                                     int msgType, String content, long seq, long createdAt) {
    return pool.preparedQuery(SAVE_MSG_SQL)
      .execute(Tuple.of(id, senderNumericId, groupId, msgType, content, seq, createdAt))
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
  public Future<List<GroupMsgWithSender>> pullMessages(String groupId, long cursor, int limit, boolean backward) {
    String sql = backward ? PULL_MSG_BACKWARD_SQL : PULL_MSG_FORWARD_SQL;
    return pool.preparedQuery(sql)
      .execute(Tuple.of(groupId, cursor, limit))
      .map(rows -> {
        List<GroupMsgWithSender> list = new ArrayList<>();
        for (Row row : rows) {
          list.add(new GroupMsgWithSender(
            row.getLong("id"), row.getLong("sender_id"), row.getString("group_id"),
            row.getInteger("msg_type"), row.getString("content"),
            row.getLong("seq"), row.getLong("created_at")));
        }
        return list;
      });
  }

  private GroupInfo rowToGroupInfo(Row row) {
    return GroupInfo.builder()
      .id(row.getLong("id"))
      .groupId(row.getString("group_id"))
      .name(row.getString("name"))
      .avatar(row.getString("avatar"))
      .description(row.getString("description"))
      .ownerId(row.getString("owner_id"))
      .memberCount(row.getInteger("member_count"))
      .maxMembers(row.getInteger("max_members"))
      .createdAt(row.getLong("created_at"))
      .updatedAt(row.getLong("updated_at"))
      .build();
  }
}
