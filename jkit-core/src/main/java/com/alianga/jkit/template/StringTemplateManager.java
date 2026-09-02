package com.alianga.jkit.template;

import com.alianga.jkit.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 模板管理
 *
 * @time 2021/9/21 17:39
 */
public class StringTemplateManager {
    // 模板列表
    private static Map<String, StringTemplate> resourceTemplates = new HashMap<String, StringTemplate>();

    /**
     * 获取资源模板对象
     *
     * @param resource 资源路径，若不以 {@code /} 开头会自动补全前缀
     * @return 资源对应的模板对象（同一资源会被缓存复用）；资源路径为空或资源内容读取不到时返回 {@code null}
     */
    public static synchronized StringTemplate getStringTemplate(String resource) {
        if (StringUtils.isBlank(resource)) {
            return null;
        }
        if (!resource.startsWith("/")) {
            resource = "/" + resource;
        }
        if (resourceTemplates.containsKey(resource)) {
            return resourceTemplates.get(resource);
        }
        String templateSource = StringUtils.fromResource(resource);
        if (templateSource == null) {
            return null;
        }
        StringTemplate template = new StringTemplate(templateSource);
        resourceTemplates.put(resource, template);
        return template;
    }

}
