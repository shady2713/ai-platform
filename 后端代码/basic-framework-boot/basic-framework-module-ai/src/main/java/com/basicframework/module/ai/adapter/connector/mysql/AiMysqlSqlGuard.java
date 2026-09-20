package com.basicframework.module.ai.adapter.connector.mysql;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_OBJECT_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CONNECTOR_SQL_NOT_READ_ONLY;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 只读 SQL 守卫（D03）：执行前的**最后一道**结构校验。
 *
 * <p>为什么在"服务端只读会话 + 只读账号"之外还要守卫：只读会话挡的是**写**，
 * 挡不住读越权（跨库、读 information_schema 枚举）、文件函数与"一条语句里塞第二条"。
 * 守卫做三件事，任一不满足即拒绝（拒绝而不是转义）：
 *
 * <ol>
 *   <li><b>单条只读语句</b>：必须以 {@code SELECT}/{@code WITH} 开头，禁止分号、注释与用户变量；</li>
 *   <li><b>关键字黑名单</b>：DML/DDL/DCL、文件函数（{@code LOAD_FILE}/{@code OUTFILE}/{@code DUMPFILE}）、
 *       锁与事务控制（{@code FOR UPDATE}/{@code LOCK}/{@code SET}/{@code CALL}）、
 *       以及可被用来空耗上游的 {@code SLEEP}/{@code BENCHMARK}；</li>
 *   <li><b>对象白名单</b>：{@code FROM}/{@code JOIN} 引用的每个表/视图（含逗号连接与子查询内的引用）
 *       都必须在连接器授权白名单内；未带库名时按连接器自己的库判定，因此跨库与未授权表在读之前就被挡住。</li>
 * </ol>
 *
 * <p>守卫是纯函数：输入 SQL 与授权目标，输出"通过"或稳定错误码；不连接数据库、不依赖运行时状态。
 */
public final class AiMysqlSqlGuard {

    /** 禁止出现的关键字（整词匹配，大小写不敏感）。 */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "insert",
            "update",
            "delete",
            "replace",
            "merge",
            "upsert",
            "drop",
            "alter",
            "create",
            "truncate",
            "rename",
            "grant",
            "revoke",
            "call",
            "set",
            "lock",
            "unlock",
            "outfile",
            "dumpfile",
            "load_file",
            "into",
            "handler",
            "do",
            "use",
            "prepare",
            "execute",
            "deallocate",
            "begin",
            "commit",
            "rollback",
            "savepoint",
            "analyze",
            "optimize",
            "repair",
            "flush",
            "kill",
            "shutdown",
            "install",
            "uninstall",
            "sleep",
            "benchmark");

    /** FROM/JOIN 之后可以出现在表引用列表里的连接词。 */
    private static final Set<String> LIST_TERMINATORS = Set.of(
            "where",
            "on",
            "group",
            "order",
            "limit",
            "having",
            "union",
            "window",
            "for",
            "using",
            "as",
            "left",
            "right",
            "inner",
            "outer",
            "cross",
            "natural",
            "straight_join",
            "lateral",
            "force",
            "ignore",
            "use");

    /** 词元：标识符（含 schema.object）、括号、逗号。 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*|[(),]");

    private static final int MAX_SQL_LENGTH = 8_192;

    private AiMysqlSqlGuard() {}

    /** 校验 SQL 是否是可对目标执行的单条只读查询（不满足即抛稳定错误码）。 */
    public static void validate(String sql, AiMysqlConnectionTarget target) {
        if (!StringUtils.hasText(sql) || sql.length() > MAX_SQL_LENGTH) {
            throw exception(AI_CONNECTOR_SQL_NOT_READ_ONLY);
        }
        String withoutLiterals = stripStringLiterals(sql);
        if (withoutLiterals.indexOf(';') >= 0
                || withoutLiterals.contains("--")
                || withoutLiterals.contains("/*")
                || withoutLiterals.contains("#")
                || withoutLiterals.contains("@")
                || withoutLiterals.indexOf('`') >= 0) {
            // 反引号标识符一律拒绝：SQL 由平台编译生成（只产生规范标识符），
            // 支持引号只会引入"引号内内容算不算结构"的判定歧义，收益为零。
            throw exception(AI_CONNECTOR_SQL_NOT_READ_ONLY);
        }
        String head = withoutLiterals.trim().toLowerCase(Locale.ROOT);
        if (!head.startsWith("select") && !head.startsWith("with") && !head.startsWith("(select")) {
            throw exception(AI_CONNECTOR_SQL_NOT_READ_ONLY);
        }
        List<String> tokens = tokenize(withoutLiterals);
        requireReadOnlyKeywords(tokens);
        requireAuthorizedObjects(tokens, target);
    }

    /** 关键字黑名单。 */
    private static void requireReadOnlyKeywords(List<String> tokens) {
        for (String token : tokens) {
            if (token.equals("(") || token.equals(")") || token.equals(",")) {
                continue;
            }
            if (FORBIDDEN_KEYWORDS.contains(token.toLowerCase(Locale.ROOT))) {
                throw exception(AI_CONNECTOR_SQL_NOT_READ_ONLY);
            }
        }
    }

    /**
     * FROM/JOIN 引用的每个对象都必须在授权白名单内。
     *
     * <p>表引用列表按"逗号连接"逐个取（{@code FROM a, b} 也是两个对象，不能只看第一个）；
     * 括号（子查询）不在此处展开——子查询里的 {@code FROM} 会在同一次遍历中被独立检查。
     */
    private static void requireAuthorizedObjects(List<String> tokens, AiMysqlConnectionTarget target) {
        Set<String> cteNames = collectCteNames(tokens);
        for (int index = 0; index < tokens.size(); index++) {
            String keyword = tokens.get(index).toLowerCase(Locale.ROOT);
            if (!keyword.equals("from") && !keyword.equals("join")) {
                continue;
            }
            int cursor = index + 1;
            while (cursor < tokens.size()) {
                String candidate = tokens.get(cursor);
                if (candidate.equals("(")) {
                    // 子查询或函数调用：子查询内部的 FROM 由外层遍历独立检查，这里只终止当前列表
                    break;
                }
                if (candidate.equals(")") || candidate.equals(",")) {
                    cursor++;
                    continue;
                }
                String lowered = candidate.toLowerCase(Locale.ROOT);
                if (LIST_TERMINATORS.contains(lowered)
                        || lowered.equals("select")
                        || FORBIDDEN_KEYWORDS.contains(lowered)) {
                    break;
                }
                if (!cteNames.contains(lowered)) {
                    // WITH 定义的临时结果集不是真实对象：它是本次查询里已检查过的子查询的别名
                    requireAuthorized(candidate, target);
                }
                cursor++;
                // 表引用之后若不是逗号，则列表结束（别名、ON 条件、子句等）
                if (cursor < tokens.size() && !tokens.get(cursor).equals(",")) {
                    break;
                }
            }
        }
    }

    /**
     * 收集 {@code WITH name AS (...)} 定义的临时结果集名。
     *
     * <p>CTE 名不是真实对象，不应按白名单判定；但 CTE 内部引用的表仍会被
     * {@link #requireAuthorizedObjects} 独立检查，因此"用 CTE 绕过白名单"不可行。
     */
    private static Set<String> collectCteNames(List<String> tokens) {
        Set<String> names = new java.util.HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).equalsIgnoreCase("with")) {
                continue;
            }
            int cursor = index + 1;
            while (cursor + 2 < tokens.size()) {
                String name = tokens.get(cursor);
                if (!tokens.get(cursor + 1).equalsIgnoreCase("as")
                        || !tokens.get(cursor + 2).equals("(")) {
                    break;
                }
                names.add(name.toLowerCase(Locale.ROOT));
                // 跳到该 CTE 体的匹配右括号之后，才能看到下一个 "name AS ("
                int depth = 0;
                int scan = cursor + 2;
                while (scan < tokens.size()) {
                    if (tokens.get(scan).equals("(")) {
                        depth++;
                    } else if (tokens.get(scan).equals(")")) {
                        depth--;
                        if (depth == 0) {
                            scan++;
                            break;
                        }
                    }
                    scan++;
                }
                cursor = scan;
                if (cursor < tokens.size() && tokens.get(cursor).equals(",")) {
                    cursor++;
                    continue;
                }
                break;
            }
        }
        return names;
    }

    /** 单个对象引用：{@code schema.object} 或裸对象名（按连接器自己的库判定）。 */
    private static void requireAuthorized(String reference, AiMysqlConnectionTarget target) {
        int dot = reference.indexOf('.');
        String schema = dot < 0 ? target.database() : reference.substring(0, dot);
        String object = dot < 0 ? reference : reference.substring(dot + 1);
        if (!target.authorizes(schema, object)) {
            throw exception(AI_CONNECTOR_OBJECT_NOT_AUTHORIZED);
        }
    }

    private static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(sql);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * 引号内的字符串字面量不参与结构判定（避免把 {@code 'a;b'} 这类**取值**误判成多语句），
     * 但字面量本身不能承载结构：转义与注释已在文本层被拒。
     */
    private static String stripStringLiterals(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (inSingle) {
                if (current == '\\') {
                    index++;
                } else if (current == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (current == '\\') {
                    index++;
                } else if (current == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (current == '\'') {
                inSingle = true;
                continue;
            }
            if (current == '"') {
                inDouble = true;
                continue;
            }
            result.append(current);
        }
        return result.toString();
    }

    /** 授权白名单的规范化形式（供调用方与测试使用）。 */
    public static List<String> normalized(List<String> allowedObjects) {
        return allowedObjects == null
                ? List.of()
                : allowedObjects.stream()
                        .map(object -> object.toLowerCase(Locale.ROOT))
                        .toList();
    }
}
