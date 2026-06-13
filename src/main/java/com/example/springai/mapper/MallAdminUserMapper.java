package com.example.springai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.springai.entity.MallAdminUser;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户Mapper接口
 *
 * @author weijianbo
 * @date 2026-02-04
 */
@Mapper
public interface MallAdminUserMapper extends BaseMapper<MallAdminUser> {
}
