package com.huang.yupicture.model.dto.image;

import lombok.Data;

import java.io.Serializable;

/**
 * 图片编辑请求（本人或管理员）。
 *
 * <p>只开放<b>描述性字段</b>：名称、简介、分类、标签 —— 这些是"用户对图片的描述"。
 *
 * <p><b>刻意不含的字段：</b>
 * <ul>
 *   <li>{@code url} —— 指向哪个文件是上传时确定的客观事实。允许改 url，
 *       等于允许把 A 的图片指向 B 的文件（或指向任意外链）</li>
 *   <li>{@code userId} —— <b>最危险的一个</b>：能改它就能把别人的图"过户"到自己名下，
 *       或者把自己违规的图挂到别人头上</li>
 *   <li>{@code picSize} / {@code picWidth} / {@code picHeight} / {@code picFormat} ——
 *       文件本身的属性，改这些只会让数据库和真实文件对不上</li>
 * </ul>
 *
 * <p>和 user 模块的更新接口同一个套路：DTO 的字段清单就是接收白名单，
 * 手写校验管"值合不合法"，DTO 管"这个字段压根不该被改"。两层防线各司其职。
 *
 * <p>为 {@code null} 的字段表示"不改这个字段"（靠 MP 的 NOT_NULL 更新策略实现）。
 */
@Data
public class ImageUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 要编辑的图片 id（字符串形式的雪花 id） */
    private String id;

    /** 图片名称（可空；为 null 表示不改） */
    private String name;

    /** 简介（可空；为 null 表示不改） */
    private String introduction;

    /** 分类（可空；为 null 表示不改） */
    private String category;

    /** 标签（可空；为 null 表示不改。传 JSON 数组字符串，如 ["风景","城市"]） */
    private String tags;
}
