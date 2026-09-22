package com.github.moxib.pomelo.logic.service;

/**
 * 服务条款文本（客户端经 {@code GET /api/legal/terms} 拉取，三端共用同一份，避免各自维护漂移）。
 * 改文案时同时更新 {@link #VERSION}，客户端可据此提示"条款已更新"。
 */
public final class TermsOfService {

  /** 条款版本（改文案必须同步改这里） */
  public static final String VERSION = "2026-09-22";

  public static final String TITLE = "Pomelo 服务条款";

  public static final String CONTENT = """
    一、项目性质
    本站是一个用于技术验证与学习交流的测试项目，非商业运营，不提供任何形式的生产级服务保障。

    二、禁止非法用途
    严禁利用本站从事任何违法违规活动，包括但不限于：传播违法违规信息、诈骗、骚扰他人、
    侵犯他人隐私或知识产权、传播恶意程序等。一经发现，本站将直接封禁相关账号且不另行通知，
    并视情况配合有关部门调查。

    三、无生产保障
    本站不对服务的可用性、连续性、数据完整性做任何承诺。服务可能随时中断、调整或停止，
    数据可能丢失或被清空，请勿在本站存放重要或敏感数据。

    四、账号与责任
    用户须对自己账号下的一切行为负责，请妥善保管账号与密码。因账号泄露、用户自身操作
    或不可抗力造成的损失，本站不承担责任。

    五、条款变更
    本站可能随时调整功能与本条款。条款更新后继续使用本服务，即视为接受更新后的条款。

    如不同意上述内容，请立即停止使用本站。
    """;

  private TermsOfService() {
  }
}
