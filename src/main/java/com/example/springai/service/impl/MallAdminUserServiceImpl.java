package com.example.springai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.springai.entity.MallAdminUser;
import com.example.springai.mapper.MallAdminUserMapper;
import com.example.springai.service.MallAdminUserService;
import org.springframework.stereotype.Service;

@Service
public class MallAdminUserServiceImpl  extends ServiceImpl<MallAdminUserMapper, MallAdminUser> implements MallAdminUserService {
}
