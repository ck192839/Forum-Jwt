package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.Activity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * 报名占位：行锁原子递增已抢数，grabbed >= total_stock 时返回 0。
     * 这是 Redis 预扣之后的数据库层防超卖兜底。
     */
    @Update("UPDATE db_activity SET grabbed = grabbed + 1 WHERE id = #{id} AND grabbed < total_stock")
    int grabOnce(@Param("id") int id);
}
