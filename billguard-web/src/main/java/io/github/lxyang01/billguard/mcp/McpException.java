package io.github.lxyang01.billguard.mcp;

/** MCP 传输/协议/远端工具失败。 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
