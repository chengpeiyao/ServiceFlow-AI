package com.serviceflow.ticket;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 方案提交、客户验收和历史查询的 HTTP 入口；业务授权和事务由 ResolutionService 执行。 */
@RestController
@RequestMapping("/api/v1")
public class ResolutionController {
    private final ResolutionService service;
    public ResolutionController(ResolutionService service) { this.service = service; }

    /**
     * 当前轮次任务负责人提交客户可见的解决方案。
     * @param ticketId 工单 ID
     * @param command 已完成的任务 ID、工单与任务版本以及方案正文
     * @param key 本次提交的幂等键；由服务层统一校验，重试时复用
     */
    @PostMapping("/tickets/{ticketId}/resolutions")
    @ResponseStatus(HttpStatus.CREATED)
    public ResolutionService.Submitted submit(@PathVariable long ticketId, @Valid @RequestBody ResolutionCommands.Submit command,
                                             @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.submit(ticketId, command, key);
    }

    /**
     * 工单原创建者通过或拒绝当前方案；拒绝时自动创建未派工的整改任务。
     * @param id 方案 ID，不是工单 ID
     * @param command 预期工单版本、验收决定及拒绝原因
     * @param key 本次验收的幂等键；由服务层统一校验，重试时复用
     */
    @PostMapping("/resolutions/{id}/acceptance")
    public ResolutionService.Decided decide(@PathVariable long id, @Valid @RequestBody ResolutionCommands.Decide command,
                                           @RequestHeader(value="Idempotency-Key", required=false) String key) {
        return service.decide(id, command, key);
    }

    /**
     * 按轮次查询方案、验收和整改任务关联，不返回任务内部处理记录。
     * @param ticketId 工单 ID，沿用当前工单的读取权限
     */
    @GetMapping("/tickets/{ticketId}/resolutions")
    public List<ResolutionMapper.History> history(@PathVariable long ticketId) { return service.history(ticketId); }
}
