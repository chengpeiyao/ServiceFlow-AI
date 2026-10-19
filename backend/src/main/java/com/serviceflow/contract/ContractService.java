package com.serviceflow.contract;

import com.serviceflow.shared.BusinessException;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class ContractService {
    public record Coverage(ContractMapper.Route route, ContractMapper.Policy policy) {}
    private final ContractMapper mapper;
    public ContractService(ContractMapper mapper) { this.mapper = mapper; }

    public Coverage resolve(long customerId, long contractId, String serviceType, String priority, Instant now) {
        var contract = mapper.contract(contractId, customerId);
        if (contract == null || !"ACTIVE".equals(mapper.customerStatus(customerId))
                || !"ACTIVE".equals(contract.status()) || now.isBefore(contract.validFrom())
                || !now.isBefore(contract.validUntil()) || mapper.entitlement(contractId, serviceType) != 1) {
            throw BusinessException.invalid("CONTRACT_NOT_ELIGIBLE", "合同不存在、已失效或不覆盖请求服务");
        }
        var route = mapper.route(serviceType);
        var policy = mapper.policy(contract.serviceTier(), serviceType, priority);
        if (route == null || policy == null) {
            throw new BusinessException(500, "SLA_POLICY_CONFIGURATION_ERROR", "服务路由或 SLA 策略未配置，请联系管理员");
        }
        return new Coverage(route, policy);
    }
}
