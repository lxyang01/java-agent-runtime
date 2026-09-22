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
        List<String> values = args.getOptionValues("users");
        if (values == null || values.isEmpty() || !"add".equals(values.get(0))) {
            return;
        }
        if (users.count() > 0) {
            System.out.println("用户库非空,跳过播种(现有用户数:" + users.count() + ")");
            return;
        }
        List<String> nonOption = args.getNonOptionArgs();
        // --users add <name>:name 在 nonOptionArgs(经 main 透传)
        String username = nonOption.isEmpty()
            ? firstOrNull(args.getOptionValues("username")) : nonOption.get(0);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用法:--users add <用户名> --role <角色> [--password-stdin]");
        }
        String role = firstOrNull(args.getOptionValues("role"));
        String password;
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
