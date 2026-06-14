package com.example.agent.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.agent.entity.MallAdminUser;
import com.example.agent.mapper.MallAdminUserMapper;
import com.example.agent.service.MallAdminUserService;
import org.springframework.stereotype.Service;

@Service
public class MallAdminUserServiceImpl  extends ServiceImpl<MallAdminUserMapper, MallAdminUser> implements MallAdminUserService {
}
