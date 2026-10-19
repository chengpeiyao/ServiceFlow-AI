package com.serviceflow.identity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdentityMapper {
    record User(long id, String username, String passwordHash, String roleCode, String accountStatus) {}

    @Select("SELECT id,username,password_hash,role_code,account_status FROM app_user WHERE username=#{username}")
    User byUsername(String username);

    @Select("SELECT id,username,password_hash,role_code,account_status FROM app_user WHERE id=#{id}")
    User byId(long id);

    @Select("SELECT COUNT(*) FROM customer_user WHERE customer_id=#{customerId} AND user_id=#{userId}")
    int customerMembership(long userId, long customerId);

    @Select("SELECT COUNT(*) FROM team_manager_scope WHERE manager_user_id=#{userId} AND team_id=#{teamId}")
    int managerScope(long userId, long teamId);
}
