package io.github.lxyang01.billguard.cli;

import io.github.lxyang01.billguard.auth.UserStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 管理员播种 CLI(对齐 python -m billguard.users add):
 * `java -jar billguard-web.jar --users add admin --role admin --password-stdin`
 * 存在 --users 参数时执行后退出;普通启动完全无感。
 */
@Component
public class UsersCli implements ApplicationRunner {

    private final UserStore users;

    public UsersCli(UserStore users) {
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Spring 实测(UsersCliArgsTest 锁定):--users=add 绑定值到选项;
        // "--users add" 的 add 则进 nonOptionArgs。两种形态都支持。
        List<String> nonOption = args.getNonOptionArgs();
        boolean equalsForm = args.getOptionValues("users") != null
            && !args.getOptionValues("users").isEmpty()
            && "add".equals(args.getOptionValues("users").get(0));
        boolean spaceForm = args.containsOption("users")
            && args.getOptionValues("users").isEmpty()
            && nonOption.stream().anyMatch("add"::equals);
        if (!equalsForm && !spaceForm) {
            return;
        }
        if (users.count() > 0) {
            System.out.println("用户库非空,跳过播种(现有用户数:" + users.count() + ")");
            return;
        }
        // 用户名:= 形式下是首个非选项参数;空格形式下是 add 的下一个 token
        String username = null;
        for (int i = 0; i + 1 < nonOption.size(); i++) {
            if ("add".equals(nonOption.get(i))) {
                username = nonOption.get(i + 1);
                break;
            }
        }
        if (username == null && !nonOption.isEmpty()
                && !"add".equals(nonOption.get(0))) {
            username = nonOption.get(0);   // --users=add admin 形态
        }
        if (username == null) {
            username = firstOrNull(args.getOptionValues("username"));
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用法:--users add <用户名> --role <角色> [--password <密码>|--password-stdin]");
        }
        String role = firstOrNull(args.getOptionValues("role"));
        String password;
        if (role == null && spaceForm) {
            // 空格形式:--role admin 的 admin 在 nonOption(add/用户名 之后)
            for (int i = 0; i < nonOption.size(); i++) {
                if ("--role".equals(nonOption.get(i)) && i + 1 < nonOption.size()) {
                    role = nonOption.get(i + 1);
                    break;
                }
            }
        }
        if (role == null) {
            role = "admin";
        }
        if (args.containsOption("password-stdin")) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                password = reader.readLine();
            }
        } else {
            password = firstOrNull(args.getOptionValues("password"));
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空(--password-stdin 从标准输入读一行)");
        }
        var created = users.create(username.strip(), password, role == null ? "admin" : role);
        System.out.println("已创建管理员:" + created.username() + "(角色 " + created.role() + ")");
        System.exit(0);
    }

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }
}
