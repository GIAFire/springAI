package com.example.springai.agent.tools;

import com.example.springai.entity.MallAdminRole;
import com.example.springai.service.MallAdminRoleService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MallAdminRoleTools {

    @Autowired
    private MallAdminRoleService mallAdminRoleService;


    @Tool(
            name = "findRole",
            description = """
            查询系统权限信息。
            适用于用户询问：查询系统权限信息、查看权限状态。
            不返回roleCode敏感字段。
            """
    )
    public List<MallAdminRole> findRole() {
        System.out.println("findRole");
        return mallAdminRoleService.list();
    }
}