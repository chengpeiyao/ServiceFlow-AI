package com.serviceflow.ticket;

import jakarta.validation.constraints.*;

/** 方案与验收请求模型；格式约束在绑定时校验，轮次、归属和状态约束在事务中校验。 */
public final class ResolutionCommands {
    private ResolutionCommands() {}
    /**
     * 提交方案需要同时确认工单和任务均未发生变化。
     * @param expectedTicketVersion 客户端读取到的工单版本
     * @param taskId 本轮已完成的阻塞任务 ID，必须属于当前工程师
     * @param expectedTaskVersion 客户端读取到的任务版本，独立于工单版本
     * @param summary 对客户公开的解决方案正文
     */
    public record Submit(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotNull @Positive Long taskId,
                         @NotNull @Min(0) Integer expectedTaskVersion,
                         @NotBlank @Size(max=8000) String summary) {}
    /**
     * 客户对当前待验收方案作出的决定。
     * @param expectedTicketVersion 客户端读取到的工单版本
     * @param decision ACCEPT 关闭工单；REJECT 恢复处理并触发整改
     * @param reason 拒绝时必须非空白，通过时可省略；此条件由 Service 按 decision 校验
     */
    public record Decide(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotNull @Pattern(regexp="ACCEPT|REJECT") String decision,
                         @Size(max=1000) String reason) {}
}
