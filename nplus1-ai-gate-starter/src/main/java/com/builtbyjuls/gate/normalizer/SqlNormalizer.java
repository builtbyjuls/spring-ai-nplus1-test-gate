package com.builtbyjuls.gate.normalizer;

import com.builtbyjuls.gate.interceptor.RecordedQuery;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes SQL queries and filters false positives:
 * - Drops sequence calls (nextval/currval)
 * - Normalizes IN (?, ?, ?) to IN (?)
 * - Filters out intentional pagination with strictly increasing offsets
 */
public final class SqlNormalizer {

    private static final Pattern SEQUENCE_PATTERN = Pattern.compile(
            "^\\s*select\\s+(nextval|currval)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern IN_LIST_PATTERN = Pattern.compile(
            "\\bIN\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OFFSET_PATTERN = Pattern.compile(
            "\\boffset\\s+\\?",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern FETCH_FIRST_PATTERN = Pattern.compile(
            "\\bfetch\\s+first\\s+\\?\\s+rows\\s+only",
            Pattern.CASE_INSENSITIVE
    );

    private SqlNormalizer() {
    }

    /**
     * Checks if the given raw SQL is a SELECT statement.
     *
     * @param rawSql SQL string
     * @return true if rawSql starts with SELECT (ignoring leading whitespace)
     */
    public static boolean isSelect(String rawSql) {
        if (rawSql == null) {
            return false;
        }
        String stripped = rawSql.stripLeading();
        return stripped.regionMatches(true, 0, "select", 0, 6);
    }

    /**
     * Checks if the given SQL is a sequence call (e.g. nextval or currval).
     *
     * @param rawSql SQL string
     * @return true if query calls nextval or currval
     */
    public static boolean isSequenceQuery(String rawSql) {
        if (rawSql == null) {
            return false;
        }
        return SEQUENCE_PATTERN.matcher(rawSql).matches();
    }

    /**
     * Normalizes the raw SQL string by:
     * 1. Stripping leading/trailing whitespace and semicolons.
     * 2. Collapsing consecutive whitespace to a single space.
     * 3. Collapsing varying IN (?, ?, ...) lists to IN (?).
     *
     * @param rawSql raw SQL statement
     * @return normalized SQL string
     */
    public static String normalize(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return "";
        }
        String trimmed = rawSql.trim().replaceAll(";+$", "").trim();
        String collapsedWhitespace = trimmed.replaceAll("\\s+", " ");
        return IN_LIST_PATTERN.matcher(collapsedWhitespace).replaceAll("IN (?)");
    }

    /**
     * Determines whether a list of executions of the same query represents intentional pagination.
     * Pagination is detected when the query contains an OFFSET ? parameter and the bound offset values
     * strictly increase across all consecutive executions.
     *
     * @param queries list of recorded queries for the same normalized query group
     * @return true if intentional pagination with increasing offset is detected
     */
    public static boolean isPagination(List<RecordedQuery> queries) {
        if (queries == null || queries.size() < 2) {
            return false;
        }

        String sql = queries.get(0).rawSql();
        int offsetParamIndex = findOffsetParameterIndex(sql);
        if (offsetParamIndex <= 0) {
            offsetParamIndex = findFetchFirstParameterIndex(sql);
        }
        if (offsetParamIndex <= 0) {
            return false;
        }

        Long previousOffset = null;
        for (RecordedQuery query : queries) {
            Long currentOffset = extractLongParameter(query, offsetParamIndex);
            if (currentOffset == null) {
                return false;
            }
            if (previousOffset != null && currentOffset <= previousOffset) {
                return false;
            }
            previousOffset = currentOffset;
        }
        return true;
    }

    /**
     * Finds the 1-based JDBC parameter index corresponding to OFFSET ? in the SQL statement.
     *
     * @param sql SQL statement
     * @return 1-based parameter index, or -1 if OFFSET ? is not present
     */
    public static int findOffsetParameterIndex(String sql) {
        if (sql == null) {
            return -1;
        }
        Matcher matcher = OFFSET_PATTERN.matcher(sql);
        if (matcher.find()) {
            int matchStart = matcher.start();
            return countQuestionMarks(sql, matchStart) + 1;
        }
        return -1;
    }

    /**
     * Finds the 1-based JDBC parameter index corresponding to FETCH FIRST ? ROWS ONLY.
     *
     * @param sql SQL statement
     * @return 1-based parameter index, or -1 if pattern is not present
     */
    public static int findFetchFirstParameterIndex(String sql) {
        if (sql == null) {
            return -1;
        }
        Matcher matcher = FETCH_FIRST_PATTERN.matcher(sql);
        if (matcher.find()) {
            int matchStart = matcher.start();
            return countQuestionMarks(sql, matchStart) + 1;
        }
        return -1;
    }

    private static int countQuestionMarks(String sql, int upToIndex) {
        int count = 0;
        int limit = Math.min(upToIndex, sql.length());
        for (int i = 0; i < limit; i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    /**
     * Extracts a numeric value for the target 1-based parameter index from a query's captured parameters.
     *
     * @param query recorded query
     * @param targetIndex 1-based parameter index
     * @return Long value or null if not found
     */
    public static Long extractLongParameter(RecordedQuery query, int targetIndex) {
        if (query == null || query.parameters() == null) {
            return null;
        }
        for (ParameterSetOperation op : query.parameters()) {
            Object[] args = op.getArgs();
            if (args != null && args.length >= 2 && args[0] instanceof Integer idx && idx == targetIndex) {
                if (args[1] instanceof Number num) {
                    return num.longValue();
                }
            }
        }
        return null;
    }
}
