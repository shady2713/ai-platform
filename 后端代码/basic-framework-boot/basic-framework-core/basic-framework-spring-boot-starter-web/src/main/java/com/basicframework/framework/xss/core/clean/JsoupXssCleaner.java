package com.basicframework.framework.xss.core.clean;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * 基于 Jsoup 的 XSS 过滤器。
 */
public class JsoupXssCleaner implements XssCleaner {

    private final Safelist safelist;

    /**
     * 用于在 src 属性使用相对路径时，强制转换为绝对路径。为空时不处理，值应为绝对路径的前缀（包含协议部分）。
     */
    private final String baseUri;

    public JsoupXssCleaner() {
        this.safelist = buildSafelist();
        this.baseUri = "";
    }

    /**
     * 构建 XSS 清理白名单。
     *
     * 默认不放行 style 属性，避免 CSS URL、expression 等样式注入绕过。
     * 如果业务确实需要富文本样式，应为富文本模块单独定义更细粒度的白名单。
     */
    private Safelist buildSafelist() {
        Safelist relaxedSafelist = Safelist.relaxed();
        relaxedSafelist.addAttributes(":all", "class");
        relaxedSafelist.addAttributes("a", "target");
        return relaxedSafelist;
    }

    @Override
    public String clean(String html) {
        return Jsoup.clean(html, baseUri, safelist, new Document.OutputSettings().prettyPrint(false));
    }
}
