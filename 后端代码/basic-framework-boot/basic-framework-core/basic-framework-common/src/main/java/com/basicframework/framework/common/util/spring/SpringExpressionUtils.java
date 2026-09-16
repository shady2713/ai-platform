package com.basicframework.framework.common.util.spring;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Spring EL 表达式的工具类
 *
 */
public final class SpringExpressionUtils {

    /**
     * Spring EL 表达式解析器
     */
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    private SpringExpressionUtils() {}

    /**
     * 从 Bean 工厂，解析 EL 表达式的结果
     *
     * @param expressionString EL 表达式
     * @return 执行界面
     */
    public static Object parseExpression(String expressionString) {
        if (StrUtil.isBlank(expressionString)) {
            return null;
        }
        Expression expression = EXPRESSION_PARSER.parseExpression(expressionString);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new BeanFactoryResolver(SpringUtil.getApplicationContext()));
        return expression.getValue(context);
    }
}
