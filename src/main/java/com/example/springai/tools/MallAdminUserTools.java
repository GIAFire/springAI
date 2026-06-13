package com.example.springai.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.springai.entity.MallAdminUser;
import com.example.springai.service.MallAdminUserService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MallAdminUserTools {

    @Autowired
    private MallAdminUserService mallAdminUserService;


    @Tool(
            name = "findUser",
            description = """
            根据用户姓名查询用户基础信息。
            适用于用户询问：查询某个用户信息、查看用户状态、查看用户基本资料。
            不返回密码、身份证、token 等敏感字段。
            """
    )
    public MallAdminUser findUser(@ToolParam(description = "用户名或真实姓名，不可为空", required = true) String username) {
        System.out.println("findUser");
        return mallAdminUserService.getOne(new LambdaQueryWrapper<>(MallAdminUser.class)
                .eq(MallAdminUser::getUsername, username));
    }
}