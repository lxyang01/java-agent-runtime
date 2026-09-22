package io.github.lxyang01.billguard.auth;

import java.util.List;

/** 用户库端口(PG 实现见 storage 包)。 */
public interface UserStore {

    User create(String username, String password, String role);

    User get(String username);

    List<User> list();

    int count();

    User verify(String username, String password);

    User setRole(String username, String role);

    void resetPassword(String username, String password);

    void delete(String username);

    User setDisabled(String username, boolean disabled);
}
