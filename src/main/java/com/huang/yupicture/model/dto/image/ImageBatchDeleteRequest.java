package com.huang.yupicture.model.dto.image;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量删除图片请求。
 *
 * <p>id 列表放请求体而不是 URL：批量动作的参数天然是多字段集合，
 * 与单条删除「单字段走 URL 参数」的约定不冲突（那条约定管的是单条）。
 */
@Data
public class ImageBatchDeleteRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 要删除的图片 id 列表（字符串形式的雪花 id）。
     *
     * <p>非空、数量 ≤ {@code ImageService.BATCH_MAX_SIZE}，超限整体报参数错误
     * （不做「截断到前 100 条」——用户勾了 120 张却只删了 100 张，
     * 静默丢行为比明确拒绝危险得多）。
     */
    private List<String> imageIds;
}
