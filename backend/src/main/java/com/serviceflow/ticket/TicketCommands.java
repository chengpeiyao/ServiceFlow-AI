package com.serviceflow.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** 工单请求模型；注解校验参数格式，权限、状态及跨字段规则由 Service 校验。 */
public final class TicketCommands {
    private TicketCommands() {}

    /**
     * 创建工单的客户申报，不接受客户端直接指定优先级、负责团队或 SLA 版本。
     * @param customerId 工单所属客户，需与当前登录人的客户关系匹配
     * @param contractId 提供服务权益的合同，需属于该客户且在有效期内
     * @param title 问题标题
     * @param description 保留为原始客户描述，后续受理不覆盖此字段
     * @param serviceType 合同覆盖的服务类型编码
     * @param impact 影响程度，与 urgency 一起决定优先级
     * @param urgency 紧急程度
     * @param reportedProductCode 可选的客户申报产品编码，需在配置的产品范围内
     * @param reportedProductVersion 可选的客户申报产品版本
     */
    public record Create(@NotNull @Positive Long customerId, @NotNull @Positive Long contractId,
                         @NotBlank @Size(max=240) String title,
                         @NotBlank @Size(max=4000) String description,
                         @NotBlank @Size(max=64) String serviceType,
                         @NotBlank @Pattern(regexp="HIGH|MEDIUM|LOW") String impact,
                         @NotBlank @Pattern(regexp="HIGH|MEDIUM|LOW") String urgency,
                         @Size(max=80) String reportedProductCode, @Size(max=80) String reportedProductVersion) {}

    /**
     * 追加处理信息；产品申报仅允许客户在待受理阶段补充。
     * @param expectedTicketVersion 客户端读取到的工单版本，用于拒绝过期写入
     * @param content 消息正文
     * @param customerVisible 是否对客户可见，未传时默认公开；客户不能提交内部备注
     * @param reportedProductCode 可选的新产品编码；换产品且未传新版本时会清除旧版本
     * @param reportedProductVersion 可选的新产品版本，未传通常保留原值
     */
    public record Message(@NotNull @Min(0) Integer expectedTicketVersion,
                          @NotBlank @Size(max=4000) String content, Boolean customerVisible,
                          @Size(max=80) String reportedProductCode, @Size(max=80) String reportedProductVersion) {}

    /**
     * 主动取消工单的请求。
     * @param expectedTicketVersion 客户端读取到的工单版本；新操作必须匹配锁内最新版本
     * @param reason 必填取消原因，会写入客户可见活动、SLA 事件和审计
     */
    public record Cancel(@NotNull @Min(0) Integer expectedTicketVersion,
                         @NotBlank @Size(max=500) String reason) {}

    /**
     * 工单列表筛选条件；空条件不参与筛选，所有条件与服务端权限范围取交集。
     * @param page 从 1 开始的页码，缺省为 1；offset() 换算为从 0 开始的记录偏移量
     * @param size 每页条数，缺省为 20，最多 100
     * @param status 可选的工单状态，支持单独查询已取消工单
     * @param priority 可选的优先级 P1、P2 或 P3
     * @param customerId 可选的客户 ID，不代表获得该客户的访问权限
     * @param ownerTeamId 可选的负责团队 ID，不代表获得该团队的访问权限
     * @param keyword 标题子串；去除首尾空白，空串不筛选，百分号和下划线不作为通配符
     */
    public record Query(@Min(1) @Max(1000000) Integer page,
                        @Min(1) @Max(100) Integer size,
                        @Pattern(regexp="SUBMITTED|TRIAGED|ASSIGNED|IN_PROGRESS|PENDING_ACCEPTANCE|CLOSED|CANCELLED") String status,
                        @Pattern(regexp="P1|P2|P3") String priority,
                        @Positive Long customerId, @Positive Long ownerTeamId,
                        @Size(max=120) String keyword) {
        public Query {
            page = page == null ? 1 : page;
            size = size == null ? 20 : size;
            keyword = keyword == null ? null : keyword.strip();
        }
        public long offset() { return (page.longValue()-1)*size; }
    }

    /**
     * 人工确认的事实及可追溯来源。
     * @param field 事实字段名
     * @param value 确认后的字段值
     * @param sourceQuote 必须实际存在于所选来源中的客户原话
     * @param sourceMessageId 客户补充消息 ID；为 null 时引用工单原始描述
     */
    public record Fact(@NotBlank @Size(max=64) String field, @NotBlank @Size(max=1000) String value,
                       @NotBlank @Size(max=1000) String sourceQuote, @Positive Long sourceMessageId) {}
    /** 待向客户追问的信息：field 标识缺失字段，question 是具体问题。 */
    public record Followup(@NotBlank @Size(max=64) String field, @NotBlank @Size(max=500) String question) {}
    /**
     * 人工诊断建议，区分假设与有依据的结论。
     * @param content 建议内容
     * @param claimType HYPOTHESIS 为假设；EVIDENCE_BASED 必须有适用的知识引用
     * @param knowledgeRefs 知识版本 ID 列表；Service 校验审批状态及产品、版本适用范围
     */
    public record Diagnostic(@NotBlank @Size(max=1500) String content,
                             @NotBlank @Pattern(regexp="HYPOTHESIS|EVIDENCE_BASED") String claimType,
                             @NotNull @Size(max=5) List<@Positive Long> knowledgeRefs) {}
    /**
     * 客服确认的受理结果，与客户原始描述分开保存。
     * @param expectedTicketVersion 客户端读取到的工单版本
     * @param proposalId 预留的 AI 草稿 ID；当前必须为空，非空返回 AI_TRIAGE_NOT_READY
     * @param confirmedCategory 人工确认的分类
     * @param confirmedFacts 有客户原文支持的事实列表
     * @param confirmedFollowups 仍需追问的问题列表
     * @param confirmedDiagnostics 已确认的诊断建议列表
     * @param humanNotes 可选的人工说明
     */
    public record Triage(@NotNull @Min(0) Integer expectedTicketVersion, @Positive Long proposalId,
                         @NotBlank @Pattern(regexp="DATABASE_CONNECTION|NETWORK|APPLICATION|ACCOUNT_PERMISSION|CONFIGURATION|OTHER") String confirmedCategory,
                         @NotNull @Size(max=20) List<@Valid Fact> confirmedFacts,
                         @NotNull @Size(max=10) List<@Valid Followup> confirmedFollowups,
                         @NotNull @Size(max=8) List<@Valid Diagnostic> confirmedDiagnostics,
                         @Size(max=2000) String humanNotes) {}
}
