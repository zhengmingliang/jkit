package com.alianga.jkit.storage.webdav.versioned;

/**
 * 远端存储能力探测结果（通用分类，不绑定具体服务器实现）。
 *
 * @author 郑明亮
 */
public final class StoreCapabilities {
    private final boolean writable;
    private final boolean conditionalPut;
    private final OverwritePolicy overwritePolicy;
    private final boolean strongEtag;
    private final boolean replaceHeader;
    private final String deleteWarning;

    /** 已存在文件时的 PUT 行为。 */
    public enum OverwritePolicy {
        /** If-None-Match:* 得 412；或探测确认会拒绝覆盖。 */
        REJECTS_OR_412,
        /** 忽略条件头并静默覆盖。 */
        OVERWRITES_SILENTLY,
        /** 改名另存（201 + Location ≠ 请求路径）。 */
        RENAMES_ON_EXISTS,
        /** 尚未探测。 */
        UNKNOWN
    }

    public StoreCapabilities(boolean writable, boolean conditionalPut, OverwritePolicy overwritePolicy,
                             boolean strongEtag, boolean replaceHeader, String deleteWarning) {
        this.writable = writable;
        this.conditionalPut = conditionalPut;
        this.overwritePolicy = overwritePolicy == null ? OverwritePolicy.UNKNOWN : overwritePolicy;
        this.strongEtag = strongEtag;
        this.replaceHeader = replaceHeader;
        this.deleteWarning = deleteWarning;
    }

    public static StoreCapabilities unreachable() {
        return new StoreCapabilities(false, false, OverwritePolicy.UNKNOWN, false, false, null);
    }

    public static StoreCapabilities readOnly() {
        return new StoreCapabilities(false, false, OverwritePolicy.UNKNOWN, false, false, null);
    }

    public boolean writable() {
        return writable;
    }

    public boolean conditionalPut() {
        return conditionalPut;
    }

    public OverwritePolicy overwritePolicy() {
        return overwritePolicy;
    }

    public boolean strongEtag() {
        return strongEtag;
    }

    public boolean replaceHeader() {
        return replaceHeader;
    }

    public String deleteWarning() {
        return deleteWarning;
    }

    /** 状态行摘要（界面/日志）。 */
    public String summary() {
        if (!writable) {
            return "可读不可写";
        }
        StringBuilder sb = new StringBuilder("可写");
        if (conditionalPut) {
            sb.append(" · 条件写(412)");
        } else {
            sb.append(" · 无条件写");
        }
        switch (overwritePolicy) {
            case REJECTS_OR_412:
                sb.append(" · ").append("拒绝覆盖");
                break;
            case OVERWRITES_SILENTLY:
                sb.append(" · ").append("静默覆盖");
                break;
            case RENAMES_ON_EXISTS:
                sb.append(" · ").append("冲突改名");
                break;
            default:
                sb.append(" · ").append("覆盖策略未知");
                break;
        }
        if (strongEtag) {
            sb.append(" · 强ETag");
        } else {
            sb.append(" · 无强ETag");
        }
        if (replaceHeader) {
            sb.append(" · replace:1");
        }
        return sb.toString();
    }
}
