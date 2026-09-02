package com.alianga.jkit.json;

/**
 * provide configuration (control) related to initializing and loading VMs
 * <p>
 * If you want to manually configure it to take effect, you need to set it before initializing the JSON
 * class（JSON第一次调用之前）
 */
public final class JSONVmOptions {
    // vm 关闭intrinsic-candidate优化
    /**
     * 关闭 intrinsic-candidate 优化的系统属性 key
     */
    public static final String VM_OPTION_INTRINSIC_CANDIDATE_DISABLED_KEY = "jkit.json.intrinsic-candidate.disabled";
    // vm 关闭向量优化
    /**
     * 关闭 incubator vector 向量优化的系统属性 key
     */
    public static final String VM_OPTION_INCUBATOR_VECTOR_DISABLED_KEY = "jkit.json.incubator.vector.disabled";
    // 内存对齐
    /**
     * 强制要求内存对齐的系统属性 key
     */
    public static final String VM_OPTION_REQUIRED_MEMORY_ALIGNMENT_KEY = "jkit.json.required-memory-alignment";

    // disabled intrinsic-candidate
    static boolean intrinsicCandidateDisabled;
    // disabled incubator.vector api
    static boolean incubatorVectorDisabled;
    static boolean requiredMemoryAlignment;

    /**
     * force disabled <br> -> -Djkit.json.intrinsic-candidate.disabled=true
     */
    public static void disableIntrinsicCandidate() {
        intrinsicCandidateDisabled = true;
    }

    /**
     * force disabled <br> -> -Djkit.json.incubator.vector.disabled=true
     */
    public static void disableIncubatorVector() {
        incubatorVectorDisabled = true;
    }

    /**
     * force required memory align <br> -> -Djkit.json.required-memory-alignment=true
     */
    public static void forceRequiredMemoryAlignment() {
        requiredMemoryAlignment = true;
    }

    /**
     * 判断是否已关闭 intrinsic-candidate 优化。
     *
     * @return 代码中已调用 {@link #disableIntrinsicCandidate()} 或对应系统属性为 true 时返回 {@code true}，
     * 否则返回 {@code false}
     */
    public static boolean isIntrinsicCandidateDisabled() {
        return intrinsicCandidateDisabled ||
                "true".equalsIgnoreCase(System.getProperty(VM_OPTION_INTRINSIC_CANDIDATE_DISABLED_KEY));
    }

    /**
     * 判断是否已关闭 incubator vector 向量优化。
     *
     * @return 代码中已调用 {@link #disableIncubatorVector()} 或对应系统属性为 true 时返回 {@code true}，
     * 否则返回 {@code false}
     */
    public static boolean isIncubatorVectorDisabled() {
        return incubatorVectorDisabled ||
                "true".equalsIgnoreCase(System.getProperty(VM_OPTION_INCUBATOR_VECTOR_DISABLED_KEY));
    }

    /**
     * 是否需要内存对齐
     *
     * @return 代码中已调用 {@link #forceRequiredMemoryAlignment()} 或对应系统属性为 true 时返回 {@code true}，
     * 否则返回 {@code false}
     */
    public static boolean isRequiredMemoryAlignment() {
        return requiredMemoryAlignment ||
                "true".equalsIgnoreCase(System.getProperty(VM_OPTION_REQUIRED_MEMORY_ALIGNMENT_KEY));
    }
}
