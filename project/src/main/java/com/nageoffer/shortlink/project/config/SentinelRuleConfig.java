package com.nageoffer.shortlink.project.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 初始化限流配置
 */
@Component
public class SentinelRuleConfig implements InitializingBean {

    /**
     * 在 Spring Bean 初始化完成后，配置接口的限流规则
     *
     * @throws Exception 可能抛出的异常
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        // 创建一个空的限流规则列表
        List<FlowRule> rules = new ArrayList<>();

        // 为接口 create_short-link 配置限流规则
        FlowRule createOrderRule = new FlowRule();
        // 设置资源名为 "create_short-link"
        createOrderRule.setResource("create_short-link");
        // 设置流控模式为 QPS
        createOrderRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        // 设置允许通过的 QPS 数量为 1
        createOrderRule.setCount(1);
        // 将该规则加入规则列表
        rules.add(createOrderRule);

        // 加载限流规则
        FlowRuleManager.loadRules(rules);
    }
}