package com.huang.yupicture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huang.yupicture.model.entity.Image;

/**
 * 图片 Mapper。
 *
 * <p>继承 {@code BaseMapper<Image>} 即获得基础 CRUD（insert / selectById / updateById /
 * deleteById / selectPage ...）。分页插件已经在 {@code MybatisPlusConfig} 里注册过了，
 * 所以 {@code selectPage} 能用 —— <b>不注册插件时它不报错，只会静默返回全表数据</b>，
 * 这是 MyBatis-Plus 最阴的坑。
 *
 * <p>不用写 {@code @Mapper}：{@code MybatisPlusConfig} 上的
 * {@code @MapperScan("com.huang.yupicture.mapper")} 已经覆盖这个包。
 *
 * <p>目前没有自定义 SQL，所以<b>没有配套的 XML 文件</b>。
 * 需要在 XML 里写复杂查询时，再在 {@code resources/mapper/} 下建同名 XML。
 */
public interface ImageMapper extends BaseMapper<Image> {
}
