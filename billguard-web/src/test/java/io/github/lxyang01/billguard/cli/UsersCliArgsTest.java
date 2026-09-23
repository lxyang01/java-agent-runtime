package io.github.lxyang01.billguard.cli;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** Spring DefaultApplicationArguments 的实测语义(锁定,指导 UsersCli 解析)。 */
class UsersCliArgsTest {

    @Test
    void space_form_puts_values_into_nonOptionArgs() {
        var args = new DefaultApplicationArguments(new String[] {
            "--users", "add", "admin", "--role", "admin", "--password", "X",
            "--server.port=0"});
        Assertions.assertEquals(List.of(), args.getOptionValues("users"));
        Assertions.assertEquals(List.of("add", "admin", "admin", "X"),
            args.getNonOptionArgs());
    }

    @Test
    void equals_form_binds_values_to_options() {
        var args = new DefaultApplicationArguments(new String[] {
            "--users=add", "admin", "--role=admin", "--password=X"});
        Assertions.assertEquals(List.of("add"), args.getOptionValues("users"));
        Assertions.assertEquals(List.of("admin"), args.getOptionValues("role"));
        Assertions.assertEquals(List.of("X"), args.getOptionValues("password"));
        Assertions.assertEquals(List.of("admin"), args.getNonOptionArgs());
    }
}
