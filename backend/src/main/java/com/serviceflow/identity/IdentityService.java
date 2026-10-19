package com.serviceflow.identity;

import cn.dev33.satoken.stp.StpUtil;
import com.serviceflow.shared.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
    public record Actor(long id, String role) {
        public void requireRole(String expected) {
            if (!expected.equals(role)) throw new BusinessException(403, "RESOURCE_FORBIDDEN", "当前角色无权执行该操作");
        }
    }
    public record LoginResult(String token, String tokenType, long userId, String role) {}
    private final IdentityMapper mapper;
    private final PasswordEncoder passwords;

    public IdentityService(IdentityMapper mapper, PasswordEncoder passwords) {
        this.mapper = mapper;
        this.passwords = passwords;
    }

    public LoginResult login(String username, String password) {
        var user = mapper.byUsername(username);
        if (user == null || !"ACTIVE".equals(user.accountStatus()) || !passwords.matches(password, user.passwordHash())) {
            throw new BusinessException(401, "LOGIN_FAILED", "用户名或密码错误，或账号已停用");
        }
        StpUtil.login(user.id());
        return new LoginResult(StpUtil.getTokenValue(), "Bearer", user.id(), user.roleCode());
    }

    public Actor currentActor() {
        var user = mapper.byId(StpUtil.getLoginIdAsLong());
        if (user == null || !"ACTIVE".equals(user.accountStatus())) {
            StpUtil.logout();
            throw new BusinessException(401, "UNAUTHENTICATED", "登录账号已失效");
        }
        return new Actor(user.id(), user.roleCode());
    }

    public void requireCustomer(Actor actor, long customerId) {
        actor.requireRole("CUSTOMER_CONTACT");
        if (mapper.customerMembership(actor.id(), customerId) != 1) throw BusinessException.forbidden();
    }

    public boolean manages(Actor actor, long teamId) {
        return mapper.managerScope(actor.id(), teamId) == 1;
    }
}
