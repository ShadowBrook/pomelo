#!/usr/bin/env bash
set -euo pipefail

# 清理 pomelo-benchmark 压测残留数据：bench_* 用户 + 他们的好友关系 + 单聊消息。
# 通过 docker exec 进入 PostgreSQL 容器执行 psql（容器内走本地 socket，默认 trust 免密）。
#
# 用法（在部署目录 ~/pomelo 下执行）：
#   ./scripts/cleanup-bench-data.sh              # 打印待删数量 → 交互确认 → 删除
#   ./scripts/cleanup-bench-data.sh --dry-run    # 只打印数量，事务内 ROLLBACK，不落任何改动
#   ./scripts/cleanup-bench-data.sh --yes        # 非交互直接删（ssh 一次性执行用）
#
# 环境变量覆盖：POMELO_PG_CONTAINER / POMELO_PG_USER / POMELO_PG_DB / POMELO_BENCH_PATTERN
#
# 只清这三张表（压测工具也只写这三张）。压测用户在 seqsvr 存储与 Redis 里的
# seq/邮箱水位残留对真实用户无影响，不要手工清理——seqsvr 存储是 mmap 文件，
# 直接改动可能导致重复发号。

CONTAINER="${POMELO_PG_CONTAINER:-pomelo-postgres}"
DB_USER="${POMELO_PG_USER:-pomelo}"
DB_NAME="${POMELO_PG_DB:-pomelo_db}"
# SQL LIKE 模式：bench\_% 只匹配字面量 bench_ 前缀，不会误伤 bencher 这类用户名
PATTERN="${POMELO_BENCH_PATTERN:-bench\_%}"

usage() {
  sed -n '3,14p' "$0"
}

DRY_RUN=0
ASSUME_YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    -y | --yes) ASSUME_YES=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      printf '未知参数: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  printf '找不到容器 %s：请在部署目录（~/pomelo）执行，或用 POMELO_PG_CONTAINER 指定容器名\n' "$CONTAINER" >&2
  exit 1
fi

# 一律从 stdin 读 SQL：psql 的 -c 不做 :'pattern' 变量替换，只有脚本输入才做。
# 额外参数透传给 psql（读数用 -t -A -F $'\t'）。
psql_stdin() {
  docker exec -i "$CONTAINER" psql -X -v ON_ERROR_STOP=1 \
    -U "$DB_USER" -d "$DB_NAME" -v pattern="$PATTERN" "$@"
}

read -r BENCH_USERS BENCH_FRIEND_ROWS BENCH_MESSAGES <<<"$(psql_stdin -t -A -F $'\t' <<'SQL'
SELECT
  (SELECT count(*) FROM im_user WHERE user_name LIKE :'pattern'),
  (SELECT count(*) FROM im_friend
     WHERE user_id IN (SELECT id FROM im_user WHERE user_name LIKE :'pattern')
        OR friend_id IN (SELECT id FROM im_user WHERE user_name LIKE :'pattern')),
  (SELECT count(*) FROM im_message_c2c
     WHERE sender_id IN (SELECT id FROM im_user WHERE user_name LIKE :'pattern')
        OR recipient_id IN (SELECT id FROM im_user WHERE user_name LIKE :'pattern'))
SQL
)"

printf '容器=%s 库=%s 模式=%s\n' "$CONTAINER" "$DB_NAME" "$PATTERN"
printf '待清理: 用户 %s 个、好友关系 %s 行、单聊消息 %s 条\n' "$BENCH_USERS" "$BENCH_FRIEND_ROWS" "$BENCH_MESSAGES"

if [ "$BENCH_USERS" -eq 0 ]; then
  printf '没有匹配的压测用户，无需清理\n'
  exit 0
fi

if [ "$DRY_RUN" -eq 1 ]; then
  printf '\n--dry-run：在事务里执行同样的删除后回滚，用于确认影响范围\n'
else
  if [ "$ASSUME_YES" -ne 1 ]; then
    read -r -p '确认删除以上数据? [y/N] ' answer
    case "$answer" in
      y | Y | yes | YES) ;;
      *)
        printf '已取消，未改动任何数据\n'
        exit 0
        ;;
    esac
  fi
fi

# 单事务：三张表要么全删要么不动。bench_uids 只算一次，避免 pattern 重复匹配；
# 表间无外键，顺序按“消息 → 好友 → 用户”，删用户前先摘掉引用它的行。
if [ "$DRY_RUN" -eq 1 ]; then
  END_STATEMENT='ROLLBACK;'
else
  END_STATEMENT='COMMIT;'
fi

psql_stdin <<SQL
BEGIN;
CREATE TEMP TABLE bench_uids ON COMMIT DROP AS
  SELECT id FROM im_user WHERE user_name LIKE :'pattern';
\echo '-- 删除单聊消息（收发任一方是压测用户）'
DELETE FROM im_message_c2c
 WHERE sender_id IN (SELECT id FROM bench_uids)
    OR recipient_id IN (SELECT id FROM bench_uids);
\echo '-- 删除好友关系'
DELETE FROM im_friend
 WHERE user_id IN (SELECT id FROM bench_uids)
    OR friend_id IN (SELECT id FROM bench_uids);
\echo '-- 删除用户'
DELETE FROM im_user
 WHERE id IN (SELECT id FROM bench_uids);
${END_STATEMENT}
SQL

if [ "$DRY_RUN" -eq 1 ]; then
  printf '\n--dry-run 完成：以上数量为将删除的行数，事务已回滚，数据未改动\n'
else
  printf '\n清理完成（上面 DELETE 后的数字即实际删除行数）\n'
fi
