package io.github.lxyang01.billguard.web;

import io.github.lxyang01.billguard.auth.User;
import io.github.lxyang01.billguard.bills.BillFilters;
import io.github.lxyang01.billguard.core.BillGuardFacade;
import io.github.lxyang01.billguard.security.TokenAuthFilter;
import io.github.lxyang01.billguard.storage.BillAnomalies;
import io.github.lxyang01.billguard.storage.BillRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务 API 全量路由(响应键与 web.py dispatch 逐一对齐)。
 * session_id 缺省 "default",空串拒绝;能力门禁由 Security 层前置完成。
 */
@RestController
public class ApiController {

    private final BillGuardFacade facade;
    private final BillRepository bills;
    private final BillAnomalies anomalies;

    public ApiController(BillGuardFacade facade, BillRepository bills, BillAnomalies anomalies) {
        this.facade = facade;
        this.bills = bills;
        this.anomalies = anomalies;
    }

    /** for_user:每次受限访问惰性播种该 owner 的默认类别(幂等)。 */
    private BillRepository scoped(User user) {
        bills.ensureUserCategories(user.username());
        return bills;
    }

    @PostMapping("/api/snapshot")
    public Map<String, Object> snapshot(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return facade.snapshot(user, sessionId(body));
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        String message = text(body.get("message"));
        if (message.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return facade.chat(user, sessionId(body), message);
    }

    @PostMapping("/api/bills/overview")
    public Map<String, Object> overview(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return scoped(user).overview(BillFilters.fromBody(body), user.username());
    }

    @PostMapping("/api/bills/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return scoped(user).query(BillFilters.fromBody(body),
            intArg(body, "page", 1), intArg(body, "page_size", 30), user.username());
    }

    @PostMapping("/api/bills/anomalies")
    public Map<String, Object> anomalies(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        scoped(user);
        return anomalies.anomalies(intArg(body, "days", 31), text(body.get("dimension"), "spike"),
            intArg(body, "limit", 8), user.username());
    }

    @PostMapping("/api/bills/import")
    public Map<String, Object> importBills(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        String filename = text(body.get("filename"), "bills.csv");
        if (filename.isEmpty()) {
            filename = "bills.csv";
        }
        if (!(body.get("csv_text") instanceof String csvText)) {
            throw new IllegalArgumentException("csv_text 不能为空");
        }
        var result = scoped(user).importBills(filename, csvText, user.username());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", result);
        payload.put("overview", bills.overview(BillFilters.EMPTY, user.username()));
        payload.put("imports", bills.imports(20, user.username()));
        payload.put("subscriptions", bills.subscriptions(user.username()));
        if (body.get("subscriptions_csv_text") instanceof String subs
            && !subs.strip().isEmpty()) {
            String subsName = text(body.get("subscriptions_filename"), "subscriptions.csv");
            payload.put("subscriptions_result", bills.importSubscriptions(
                subsName.isEmpty() ? "subscriptions.csv" : subsName, subs, user.username()));
        }
        return payload;
    }

    @PostMapping("/api/bills/categories")
    public Map<String, Object> updateTransactionCategory(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        String txId = text(body.get("tx_id"));
        String category = text(body.get("category"));
        if (txId.isEmpty() || category.isEmpty()) {
            throw new IllegalArgumentException("tx_id 与 category 不能为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", bills.updateTransactionCategory(txId, category,
            user.username(), user.username()));
        payload.put("categories", bills.categories(user.username()));
        payload.put("audits", bills.recentAudits(50, user.username()));
        return payload;
    }

    @PostMapping("/api/category-rules/save")
    public Map<String, Object> saveCategory(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        if (!(body.get("keywords") instanceof List<?> keywords)) {
            throw new IllegalArgumentException("keywords 必须是数组");
        }
        Long categoryId = body.get("category_id") instanceof Number n ? n.longValue() : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", bills.saveCategory(text(body.get("name")),
            keywords.stream().map(String::valueOf).toList(),
            !Boolean.FALSE.equals(body.get("enabled")), categoryId,
            user.username(), user.username()));
        payload.put("categories", bills.categories(user.username()));
        payload.put("audits", bills.recentAudits(50, user.username()));
        return payload;
    }

    @PostMapping("/api/category-rules/delete")
    public Map<String, Object> deleteCategory(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", bills.deleteCategory(intArg(body, "category_id", 0),
            user.username(), user.username()));
        payload.put("categories", bills.categories(user.username()));
        payload.put("audits", bills.recentAudits(50, user.username()));
        return payload;
    }

    @PostMapping("/api/category-rules/rematch")
    public Map<String, Object> rematch(@RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", bills.rematchCategories(user.username(), user.username()));
        payload.put("categories", bills.categories(user.username()));
        payload.put("overview", bills.overview(BillFilters.EMPTY, user.username()));
        return payload;
    }

    @PostMapping("/api/bills/workflow")
    public Map<String, Object> workflow(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        if (!(body.get("tx_ids") instanceof List<?> txIds)
            || !(body.get("updates") instanceof Map<?, ?> updates)) {
            throw new IllegalArgumentException("tx_ids 必须是数组,updates 必须是对象");
        }
        Map<String, Object> safe = new LinkedHashMap<>(asObjectMap(updates));
        safe.remove("operator");   // 身份一律取服务端
        String status = text(safe.get("status"));
        String note = text(safe.get("note"));
        if (status.isEmpty() || !BillRepository.WORKFLOW_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status 必须是"
                + String.join("、", BillRepository.WORKFLOW_STATUSES));
        }
        if (note.length() > 200) {
            throw new IllegalArgumentException("备注最长 200 字");
        }
        return bills.updateWorkflow(txIds.stream().map(String::valueOf).toList(),
            user.username(), status, note, user.username());
    }

    @PostMapping("/api/bills/audits")
    public Map<String, Object> audits(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return Map.of("audits", bills.transactionAudits(text(body.get("tx_id")), 50,
            user.username()));
    }

    @PostMapping("/api/bills/export")
    public Map<String, Object> export(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return Map.of("filename", "bills-export.csv",
            "csv_text", bills.exportCsv(BillFilters.fromBody(body), user.username()));
    }

    @PostMapping("/api/bills/purge")
    public Map<String, Object> purge(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purged", true);
        payload.putAll(bills.purgeOwner(user.username(), user.username(),
            text(body.get("note"), "自助清空")));
        payload.put("overview", bills.overview(BillFilters.EMPTY, user.username()));
        payload.put("sessions", facade.listSessions(user, ""));
        return payload;
    }

    @PostMapping("/api/reports/save")
    public Map<String, Object> saveReport(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report", bills.saveReport(sessionId(body), text(body.get("title")),
            text(body.get("content")), user.username()));
        payload.put("reports", bills.reports(50, user.username()));
        return payload;
    }

    @PostMapping("/api/reports/delete")
    public Map<String, Object> deleteReport(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", bills.deleteReport(intArg(body, "report_id", 0), user.username()));
        payload.put("reports", bills.reports(50, user.username()));
        return payload;
    }

    @PostMapping("/api/session/delete")
    public Map<String, Object> deleteSession(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return facade.deleteSession(user, sessionId(body));
    }

    @PostMapping("/api/approvals/list")
    public Map<String, Object> approvals(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return Map.of("approvals", facade.approvals(user, sessionId(body)));
    }

    @PostMapping("/api/approvals/decide")
    public Map<String, Object> decideApproval(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return facade.decideApproval(user, sessionId(body), body);
    }

    @PostMapping("/api/runs/list")
    public Map<String, Object> runs(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return facade.listRuns(user, sessionId(body), intArg(body, "limit", 50));
    }

    @PostMapping("/api/runs/detail")
    public Map<String, Object> runDetail(@RequestBody Map<String, Object> body,
        @RequestAttribute(TokenAuthFilter.USER_ATTRIBUTE) User user) {
        return facade.runDetail(user, sessionId(body), text(body.get("trace_id")));
    }

    @GetMapping("/api/evaluations/list")
    public Map<String, Object> evaluations() {
        return Map.of("evaluations", List.of());   // 评测报告目录 M4 接入
    }

    // ---- 小工具 ----

    private static String sessionId(Map<String, Object> body) {
        String sessionId = body.get("session_id") == null
            ? "default" : String.valueOf(body.get("session_id")).strip();
        if (sessionId.isEmpty()) {
            throw new IllegalArgumentException("session_id 不能为空");
        }
        return sessionId;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String text(Object value, String fallback) {
        String result = text(value);
        return result.isEmpty() ? fallback : result;
    }

    private static int intArg(Map<String, Object> body, String key, int fallback) {
        return body.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
