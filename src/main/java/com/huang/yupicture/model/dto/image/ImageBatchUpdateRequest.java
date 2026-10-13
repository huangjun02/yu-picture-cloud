package com.huang.yupicture.model.dto.image;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量编辑图片请求（分类 / 追加标签）。
 *
 * <p><b>两个字段的语义刻意不同：</b>
 * <ul>
 *   <li>{@code category} —— 「设置」：给了就覆盖（批量把一批图归到同一类，
 *       覆盖正是本意；不支持批量清空分类，传空白等于没传）</li>
 *   <li>{@code appendTags} —— 「追加」：并入每张图现有的标签数组再整体去重，
 *       <b>不是覆盖</b> —— 覆盖会把别人逐张精心打的标签一次清光，那不是批量操作该干的事</li>
 * </ul>
 * 两个字段都没给 → 报参数错误（没有内容的批量编辑只能是个 bug）。
 */
@Data
public class ImageBatchUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 要编辑的图片 id 列表（字符串形式的雪花 id），非空、数量 ≤ 100 */
    private List<String> imageIds;

    /** 目标分类（可选）：给了就覆盖所有选中图的分类 */
    private String category;

    /** 要追加的标签（可选）：并入每张图现有标签后整体去重 */
    private List<String> appendTags;
}
