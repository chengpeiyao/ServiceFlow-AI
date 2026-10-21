package com.serviceflow.ticket;

import com.serviceflow.sla.SlaMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 工单 HTTP 入口：绑定并校验请求参数，委托 Service 执行授权和业务事务。
 * 写接口的 Idempotency-Key 由幂等服务统一校验，required=false 不代表业务上允许缺省。
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketService service;
    public TicketController(TicketService service) { this.service = service; }

    /**
     * 分页查询当前登录人可见的工单摘要。
     * @param query URL 查询参数；客户、团队等筛选条件只能缩小当前用户的可见范围
     * @return 当前页条目、筛选后总数以及实际页码和页大小
     */
    @GetMapping
    public TicketService.Page list(@Valid @ModelAttribute TicketCommands.Query query) { return service.list(query); }

    /**
     * 由创建者或客服取消尚未进入执行阶段的工单。
     * @param id 工单 ID
     * @param command 客户端已读取的工单版本及对客户公开的取消原因
     * @param key 本次业务操作的幂等键；重试同一操作时保持不变
     * @return 取消后的状态、版本和取消时间
     */
    @PostMapping("/{id}/cancel")
    public TicketService.Cancelled cancel(@PathVariable long id, @Valid @RequestBody TicketCommands.Cancel command,
                                          @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.cancel(id, command, key);
    }

    /**
     * 提交客户原始问题，校验合同权益后创建工单并启动 SLA。
     * @param command 客户、合同、服务类型和问题描述；优先级由服务端计算
     * @param key 本次创建操作的幂等键
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketService.Created create(@Valid @RequestBody TicketCommands.Create command,
                                         @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.create(command, key);
    }

    /**
     * 读取工单详情；客户响应中会过滤内部活动。
     * @param id 工单 ID，Service 会检查当前登录人的访问权限
     */
    @GetMapping("/{id}")
    public TicketService.Details detail(@PathVariable long id) { return service.detail(id); }

    /**
     * 通过详情入口复用工单授权，返回 SLA 快照。
     * @param id 工单 ID
     */
    @GetMapping("/{id}/sla")
    public SlaMapper.Snapshot sla(@PathVariable long id) { return service.detail(id).sla(); }

    /**
     * 追加客户补充、公开答复或内部备注，不覆盖原始问题描述。
     * @param id 工单 ID
     * @param command 消息内容、可见性、工单版本及可选的产品申报
     * @param key 本次追加消息的幂等键
     */
    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketService.MessageAdded append(@PathVariable long id, @Valid @RequestBody TicketCommands.Message command,
                                              @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.append(id, command, key);
    }

    /**
     * 客服确认人工受理结果；当前尚不支持通过 AI 草稿提交受理。
     * @param id 工单 ID
     * @param command 分类、事实及来源、待追问项、诊断建议和工单版本
     * @param key 本次受理操作的幂等键
     */
    @PostMapping("/{id}/triage")
    public TicketService.Triaged triage(@PathVariable long id, @Valid @RequestBody TicketCommands.Triage command,
                                         @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.triage(id, command, key);
    }
}
