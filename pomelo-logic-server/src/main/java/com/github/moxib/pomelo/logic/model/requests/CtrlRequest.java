package com.github.moxib.pomelo.logic.model.requests;

import com.github.moxib.pomelo.proto.ctrl.CtrlProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 控制命令请求 DTO（PB CtrlReq / JSON 共用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CtrlRequest(int ctrlType, String targetUser) {
  public static CtrlRequest fromProto(CtrlProto.CtrlReq proto) {
    return new CtrlRequest(proto.getCtrlTypeValue(), proto.getTargetUser());
  }
}
