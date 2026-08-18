package com.github.moxib.pomelo.seqsvr.proto;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由表 — uid Section 到 AllocSvr 节点的全映射。
 */
public class Router {

  private int version;
  private List<RouterNode> nodeList;

  public Router() {
    this.nodeList = new ArrayList<>();
  }

  public Router(int version, List<RouterNode> nodeList) {
    this.version = version;
    this.nodeList = nodeList;
  }

  public int getVersion() { return version; }
  public void setVersion(int version) { this.version = version; }

  public List<RouterNode> getNodeList() { return nodeList; }
  public void setNodeList(List<RouterNode> nodeList) { this.nodeList = nodeList; }

  /** 判断客户端的路由表版本是否过期 */
  public boolean isStale(int clientVersion) {
    return clientVersion < version;
  }
}
