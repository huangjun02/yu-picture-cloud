package com.huang.yupicture.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 主页筛选项：出现过的分类与标签（去重、已排序）。
 *
 * <p>只返回「浏览范围内」的值 —— 即已通过审核的图上的分类/标签。
 * 待审核图上的分类不该出现在公开筛选栏里，否则等于提前泄露了还没过审的内容长什么样。
 */
@Data
public class ImageOptionsVO implements Serializable {

    /** 分类列表（去重，按出现过即保留原始大小写） */
    private List<String> categories;

    /** 标签列表（把每张图 JSON 数组里的标签拆开、去重） */
    private List<String> tags;

    private static final long serialVersionUID = 1L;
}
