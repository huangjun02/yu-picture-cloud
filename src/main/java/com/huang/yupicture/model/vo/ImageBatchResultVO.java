package com.huang.yupicture.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作结果（删除 / 编辑 / 审核共用）。
 *
 * <p><b>为什么是「部分成功」而不是「全成全败」：</b>批量删除清 COS 文件是无法回滚的
 * 物理动作 —— 删到第 5 张失败时把前 4 张「回滚」回去，数据库记录能恢复、
 * 文件已经没了，制造出来的恰恰是设计里最忌讳的「不会被发现的错误」。
 * 所以逐条尽力而为，成功的落定，失败的逐条带回原因（不存在 / 越权 / 格式非法），
 * 前端按数量展示「成功 X、失败 Y」。
 */
@Data
public class ImageBatchResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成功处理的条数 */
    private int successCount;

    /** 失败的条数 */
    private int failCount;

    /** 失败明细（id + 原因），前端可展开查看 */
    private List<FailItem> failures = new ArrayList<>();

    @Data
    public static class FailItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 失败的图片 id（请求里传的原始字符串，不做归一化） */
        private String imageId;

        /** 失败原因（人话，可直接展示） */
        private String reason;

        public FailItem() {
        }

        public FailItem(String imageId, String reason) {
            this.imageId = imageId;
            this.reason = reason;
        }
    }
}
